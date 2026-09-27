package io.pathfinder.engine.core;

import io.pathfinder.engine.command.CommandHandler;
import io.pathfinder.engine.command.CommandRegistry;
import io.pathfinder.engine.command.InMemoryCommandRegistry;
import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.model.StateDefinition;
import io.pathfinder.engine.runtime.*;

import java.util.*;

/**
 * High-level orchestration engine that coordinates state evaluation with backend command execution.
 * <p>
 * Evaluates flows using {@link DecisionEngine}, automatically dispatches backend commands to registered
 * {@link CommandHandler} implementations, and only yields when a frontend checkpoint (user interaction)
 * or terminal state is reached.
 */
public class WorkflowOrchestrator {
    private static final int DEFAULT_MAX_STEPS = 50;

    private final DecisionEngine decisionEngine;
    private final CommandRegistry commandRegistry;
    private final int maxSteps;

    public WorkflowOrchestrator(DecisionEngine decisionEngine) {
        this(decisionEngine, new InMemoryCommandRegistry(), DEFAULT_MAX_STEPS);
    }

    public WorkflowOrchestrator(DecisionEngine decisionEngine, CommandRegistry commandRegistry) {
        this(decisionEngine, commandRegistry, DEFAULT_MAX_STEPS);
    }

    public WorkflowOrchestrator(DecisionEngine decisionEngine, CommandRegistry commandRegistry, int maxSteps) {
        this.decisionEngine = decisionEngine != null ? decisionEngine : new DecisionEngine();
        this.commandRegistry = commandRegistry != null ? commandRegistry : new InMemoryCommandRegistry();
        this.maxSteps = maxSteps > 0 ? maxSteps : DEFAULT_MAX_STEPS;
    }

    public DecisionEngine getDecisionEngine() {
        return decisionEngine;
    }

    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    /**
     * Executes the workflow from the specified state, dispatching commands until user interaction is required
     * or a terminal outcome is reached.
     *
     * @param flowId the identifier of the flow to execute
     * @param currentStateId the starting state identifier (or null for initial state)
     * @param context the session context
     * @param event the triggering event (or null for start event)
     * @return the execution plan yielding a frontend challenge or terminal outcome
     */
    public ExecutionPlan run(String flowId, String currentStateId, SessionContext context, Event event) {
        String activeStateId = currentStateId;
        SessionContext activeContext = context != null ? context : new SessionContext();
        Event activeEvent = event != null ? event : Event.start();

        int stepCount = 0;

        while (stepCount++ < maxSteps) {
            ExecutionPlan plan = decisionEngine.evaluate(flowId, activeStateId, activeContext, activeEvent);

            if (plan.isTerminal()) {
                return plan;
            }

            // If the plan requires user interaction (frontend screen), yield immediately
            if (plan.hasFrontendSteps()) {
                return plan;
            }

            // Check if there are backend commands to execute
            if (plan.hasBackendSteps()) {
                boolean allHandlersAvailable = true;
                for (BackendStep step : plan.getBackendSteps()) {
                    if (!commandRegistry.hasHandler(step.getService())) {
                        allHandlersAvailable = false;
                        break;
                    }
                }

                // If not all handlers are registered, yield to external executor
                if (!allHandlersAvailable) {
                    return plan;
                }

                // Execute all backend handlers
                Map<String, Object> results = new LinkedHashMap<>();
                boolean errorOccurred = false;

                for (BackendStep step : plan.getBackendSteps()) {
                    CommandHandler handler = commandRegistry.getHandler(step.getService()).orElseThrow();
                    try {
                        Map<String, Object> output = handler.execute(step.getPayload());
                        results.put(step.getStepId(), output != null ? output : Collections.emptyMap());
                    } catch (Exception ex) {
                        errorOccurred = true;

                        // Check if the current state defines an onError fallback
                        FlowDefinition flow = decisionEngine.getFlowRegistry().getFlow(flowId).orElse(null);
                        StateDefinition state = flow != null ? flow.getState(plan.getCurrentState()) : null;

                        if (state != null && state.getOnError() != null && !state.getOnError().isBlank()) {
                            activeStateId = state.getOnError();
                            activeContext = plan.getUpdatedContext()
                                    .withValue("lastError", ex.getMessage())
                                    .withValue("failedStep", step.getStepId());
                            activeEvent = Event.resume(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
                            break;
                        } else {
                            // Fail-closed terminal error
                            return ExecutionPlan.builder()
                                    .currentState(plan.getCurrentState())
                                    .terminal(new TerminalResult("ERROR", Collections.emptyMap(),
                                            "Command '" + step.getStepId() + "' on service '" + step.getService() + "' failed: " + ex.getMessage()))
                                    .updatedContext(plan.getUpdatedContext().withValue("lastError", ex.getMessage()))
                                    .build();
                        }
                    }
                }

                if (errorOccurred) {
                    continue;
                }

                // If the state yielded a checkpoint expecting results, resume automatically with command output
                if (plan.hasCheckpoint() && plan.getCheckpoint().getExpectedInputs().contains("results")) {
                    activeStateId = plan.getCheckpoint().getResumeState();
                    activeContext = plan.getUpdatedContext();
                    activeEvent = Event.resume(results);
                    continue;
                }

                return plan;
            }

            // Neither frontend nor backend steps remaining
            return plan;
        }

        // Loop threshold exceeded
        return ExecutionPlan.builder()
                .currentState(activeStateId)
                .terminal(new TerminalResult("ERROR", Collections.emptyMap(), "Workflow exceeded maximum orchestration step limit (" + maxSteps + ")"))
                .updatedContext(activeContext)
                .build();
    }
}
