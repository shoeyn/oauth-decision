package io.pathfinder.engine.execution;

import io.pathfinder.engine.execution.eval.CelExpressionEvaluator;
import io.pathfinder.engine.execution.eval.JsonSchemaInputValidator;
import io.pathfinder.engine.execution.simulation.FlowSimulation;
import io.pathfinder.engine.execution.simulation.SimulationStep;
import io.pathfinder.engine.execution.state.BackendStep;
import io.pathfinder.engine.execution.state.Checkpoint;
import io.pathfinder.engine.execution.state.Event;
import io.pathfinder.engine.execution.state.ExecutionPlan;
import io.pathfinder.engine.execution.state.FrontendStep;
import io.pathfinder.engine.execution.state.SessionContext;
import io.pathfinder.engine.execution.state.StackFrame;
import io.pathfinder.engine.execution.state.TerminalResult;
import io.pathfinder.engine.flow.model.CommandDefinition;
import io.pathfinder.engine.flow.model.FlowDefinition;
import io.pathfinder.engine.flow.model.FrontendSchemaDefinition;
import io.pathfinder.engine.flow.model.StateDefinition;
import io.pathfinder.engine.flow.model.StateType;
import io.pathfinder.engine.flow.model.TerminalConfig;
import io.pathfinder.engine.flow.model.TransitionDefinition;
import io.pathfinder.engine.flow.registry.FlowRegistry;
import io.pathfinder.engine.flow.registry.InMemoryFlowRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public class DecisionEngine {
    private final FlowRegistry flowRegistry;
    private final CelExpressionEvaluator celEvaluator;
    private final JsonSchemaInputValidator schemaValidator;

    public DecisionEngine() {
        this(new InMemoryFlowRegistry(), new CelExpressionEvaluator(), new JsonSchemaInputValidator(JsonMapper.builder().build()));
    }

    public DecisionEngine(FlowRegistry flowRegistry) {
        this(flowRegistry, new CelExpressionEvaluator(), new JsonSchemaInputValidator(JsonMapper.builder().build()));
    }

    public DecisionEngine(CelExpressionEvaluator celEvaluator, JsonSchemaInputValidator schemaValidator) {
        this(new InMemoryFlowRegistry(), celEvaluator, schemaValidator);
    }

    public DecisionEngine(FlowRegistry flowRegistry, CelExpressionEvaluator celEvaluator, JsonSchemaInputValidator schemaValidator) {
        this.flowRegistry = flowRegistry != null ? flowRegistry : new InMemoryFlowRegistry();
        this.celEvaluator = celEvaluator != null ? celEvaluator : new CelExpressionEvaluator();
        this.schemaValidator = schemaValidator != null ? schemaValidator : new JsonSchemaInputValidator(JsonMapper.builder().build());
    }

    public FlowRegistry getFlowRegistry() {
        return flowRegistry;
    }

    public ExecutionPlan evaluate(String flowId, String currentStateId, SessionContext context, Event event) {
        FlowDefinition flow = flowRegistry.getFlow(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found in registry: " + flowId));
        return evaluate(flow, currentStateId, context, event);
    }

    public ExecutionPlan evaluate(FlowDefinition flow, String currentStateId, SessionContext context, Event event) {
        if (flow == null && (context == null || context.getCurrentFlowId() == null)) {
            throw new IllegalArgumentException("flow must not be null");
        }

        SessionContext activeContext = context != null ? context : new SessionContext();
        Event activeEvent = event != null ? event : Event.start();

        if (flow != null) {
            flowRegistry.register(flow);
        }

        // Determine the currently active flow (from context if suspended in a subflow, else root flow)
        FlowDefinition activeFlow = null;
        if (activeContext.getCurrentFlowId() != null) {
            activeFlow = flowRegistry.getFlow(activeContext.getCurrentFlowId()).orElse(null);
        }
        if (activeFlow == null) {
            activeFlow = flow;
        }
        if (activeFlow == null) {
            throw new IllegalStateException("Active flow could not be resolved from registry");
        }
        activeContext = activeContext.withCurrentFlowId(activeFlow.getId());

        // 1. Determine starting state
        boolean isInitialTurn = (currentStateId == null || currentStateId.isBlank());
        if (isInitialTurn) {
            currentStateId = activeFlow.getInitialState();
            activeContext = activeContext.withBreadcrumb(currentStateId);
        }

        StateDefinition currentState = activeFlow.getState(currentStateId);
        if (currentState == null) {
            throw new IllegalArgumentException("Unknown state in flow '" + activeFlow.getId() + "': " + currentStateId);
        }

        // 2. Check attempt limits for interactive turns
        if (!isInitialTurn && currentState.getMaxAttempts() != null && currentState.getMaxAttempts() > 0) {
            if (Event.TYPE_SUBMIT.equalsIgnoreCase(activeEvent.getType()) || Event.TYPE_RESUME.equalsIgnoreCase(activeEvent.getType())) {
                activeContext = activeContext.withIncrementedAttempt(currentStateId);
                int currentAttempts = activeContext.getAttemptCount(currentStateId);
                if (currentAttempts > currentState.getMaxAttempts()) {
                    if (currentState.getOnError() != null && !currentState.getOnError().isBlank()) {
                        currentStateId = currentState.getOnError();
                        activeContext = activeContext.withBreadcrumb(currentStateId);
                        currentState = activeFlow.getState(currentStateId);
                        if (currentState == null) {
                            throw new IllegalStateException("onError target state does not exist: " + currentStateId);
                        }
                    } else {
                        return ExecutionPlan.builder()
                                .currentState(currentStateId)
                                .terminal(new TerminalResult("DENIED", Collections.emptyMap(), "Maximum attempts exceeded for state: " + currentStateId))
                                .updatedContext(activeContext)
                                .build();
                    }
                }
            }
        }

        // 3. If this is a SUBMIT event from a frontend step, validate input against JSON Schema first
        if (Event.TYPE_SUBMIT.equalsIgnoreCase(activeEvent.getType()) && !currentState.getFrontendSchemas().isEmpty()) {
            Map<String, Object> validationBindings = new LinkedHashMap<>();
            validationBindings.put("data", activeContext.getData());
            validationBindings.put("context", activeContext.getData());
            validationBindings.put("config", activeContext.getConfig());
            validationBindings.put("input", activeEvent.getPayload() != null ? activeEvent.getPayload() : Collections.emptyMap());

            for (FrontendSchemaDefinition schemaDef : currentState.getFrontendSchemas()) {
                if (schemaDef.getJsonSchema() != null) {
                    List<String> errors = schemaValidator.validate(schemaDef.getJsonSchema(), activeEvent.getPayload());
                    if (!errors.isEmpty()) {
                        String title = celEvaluator.resolveTemplateString(schemaDef.getTitle(), validationBindings);
                        String desc = celEvaluator.resolveTemplateString(schemaDef.getDescription(), validationBindings);
                        Map<String, Object> data = celEvaluator.resolveTemplateMap(schemaDef.getData(), validationBindings);
                        FrontendStep failedStep = new FrontendStep(
                                schemaDef.getScreenId(),
                                title,
                                desc,
                                schemaDef.getJsonSchema(),
                                schemaDef.getUiSchema(),
                                data,
                                errors
                        );
                        return ExecutionPlan.builder()
                                .currentState(currentStateId)
                                .addFrontendStep(failedStep)
                                .checkpoint(new Checkpoint(activeFlow.getId(), currentStateId, List.of("input")))
                                .updatedContext(activeContext)
                                .build();
                    }
                }
            }
            // Always preserve submitted input under input namespace
            activeContext = activeContext.withInput(activeEvent.getPayload());

            // Security Hardening: Only merge declared schema properties into context.
            // Untrusted extra properties are filtered out, and pre-existing context keys CANNOT be overwritten!
            Set<String> declaredProperties = extractDeclaredProperties(currentState.getFrontendSchemas());
            Map<String, Object> safeUpdates = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : activeEvent.getPayload().entrySet()) {
                String key = entry.getKey();
                if (declaredProperties.isEmpty() || declaredProperties.contains(key)) {
                    // Do not permit untrusted submit payloads to overwrite existing context keys
                    if (!activeContext.getData().containsKey(key)) {
                        safeUpdates.put(key, entry.getValue());
                    }
                }
            }
            if (!safeUpdates.isEmpty()) {
                activeContext = activeContext.withAll(safeUpdates);
            }
        }

        // 4. Build evaluation bindings
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("context", activeContext.getData());
        bindings.put("data", activeContext.getData());
        bindings.put("config", activeContext.getConfig());
        bindings.put("event", activeEvent.getPayload());
        if (Event.TYPE_RESUME.equalsIgnoreCase(activeEvent.getType())) {
            bindings.put("results", activeEvent.getPayload());
            bindings.put("input", activeContext.getInput());
        } else if (Event.TYPE_SUBMIT.equalsIgnoreCase(activeEvent.getType())) {
            bindings.put("input", activeEvent.getPayload());
            bindings.put("results", Collections.emptyMap());
        } else {
            bindings.put("results", Collections.emptyMap());
            bindings.put("input", activeContext.getInput());
        }

        // 4. If we are resuming or submitting on an existing state, evaluate its transition first
        if (!isInitialTurn) {
            TransitionDefinition matched = findMatchingTransition(currentState, activeEvent, bindings);
            if (matched != null) {
                activeContext = applyContextUpdates(activeContext, matched.getContextUpdates(), bindings);
                currentStateId = matched.getTarget();
                activeContext = activeContext.withBreadcrumb(currentStateId);
                currentState = activeFlow.getState(currentStateId);
                if (currentState == null) {
                    throw new IllegalStateException("Transition target state does not exist: " + matched.getTarget());
                }
                bindings.put("context", activeContext.getData());
                bindings.put("data", activeContext.getData());
            }
        }

        // 5. Look-ahead accumulation loop
        ExecutionPlan.Builder planBuilder = ExecutionPlan.builder();
        Set<String> visitedInThisTurn = new HashSet<>();

        while (currentState != null) {
            String stateKey = activeFlow.getId() + ":" + currentStateId;
            planBuilder.currentState(currentStateId);

            // Avoid infinite loops in cycle graphs without external checkpoints
            if (!visitedInThisTurn.add(stateKey)) {
                planBuilder.checkpoint(new Checkpoint(activeFlow.getId(), currentStateId, List.of("cycle_detected")));
                break;
            }

            // Check if SUBFLOW delegation
            if (currentState.getType() == StateType.SUBFLOW) {
                String subflowId = currentState.getSubflow();
                if (subflowId == null || subflowId.isBlank()) {
                    throw new IllegalStateException("State '" + currentStateId + "' of type SUBFLOW must specify a 'subflow' identifier");
                }
                FlowDefinition childFlow = flowRegistry.getFlow(subflowId)
                        .orElseThrow(() -> new IllegalStateException("Subflow '" + subflowId + "' not found in registry"));

                // Push return frame onto call stack
                activeContext = activeContext.withPushedFrame(new StackFrame(activeFlow.getId(), currentStateId));
                activeFlow = childFlow;
                activeContext = activeContext.withCurrentFlowId(activeFlow.getId());
                currentStateId = activeFlow.getInitialState();
                activeContext = activeContext.withBreadcrumb(currentStateId);
                currentState = activeFlow.getState(currentStateId);
                bindings.put("context", activeContext.getData());
                bindings.put("data", activeContext.getData());
                continue;
            }

            // Check if TERMINAL
            if (currentState.getType() == StateType.TERMINAL || currentState.getTerminalConfig() != null) {
                TerminalConfig tc = currentState.getTerminalConfig() != null
                        ? currentState.getTerminalConfig()
                        : new TerminalConfig("SUCCESS", Collections.emptyMap(), null);

                Map<String, Object> resolvedClaims = celEvaluator.resolveTemplateMap(tc.getClaims(), bindings);
                Object resolvedRedirect = celEvaluator.resolveTemplateValue(tc.getRedirectUrl(), bindings);
                String resolvedRedirectUrl = resolvedRedirect != null ? resolvedRedirect.toString() : null;
                Object resolvedErrorDesc = celEvaluator.resolveTemplateValue(tc.getErrorDescription(), bindings);
                String errorDescription = resolvedErrorDesc != null ? resolvedErrorDesc.toString() : null;

                String action = tc.getAction();
                if (action == null || action.isBlank()) {
                    if (resolvedRedirectUrl != null && !resolvedRedirectUrl.isBlank()) {
                        action = TerminalResult.ACTION_REDIRECT;
                    } else if (!currentState.getFrontendSchemas().isEmpty()) {
                        action = TerminalResult.ACTION_UI;
                    } else {
                        action = TerminalResult.ACTION_COMPLETE;
                    }
                }

                // If this terminal state has frontend schemas (e.g. hard UI dropout screen), accumulate them!
                for (FrontendSchemaDefinition schemaDef : currentState.getFrontendSchemas()) {
                    String title = celEvaluator.resolveTemplateString(schemaDef.getTitle(), bindings);
                    String desc = celEvaluator.resolveTemplateString(schemaDef.getDescription(), bindings);
                    Map<String, Object> data = celEvaluator.resolveTemplateMap(schemaDef.getData(), bindings);
                    planBuilder.addFrontendStep(new FrontendStep(
                            schemaDef.getScreenId(),
                            title,
                            desc,
                            schemaDef.getJsonSchema(),
                            schemaDef.getUiSchema(),
                            data,
                            Collections.emptyList()
                    ));
                }

                TerminalResult termResult = new TerminalResult(
                        tc.getStatus(),
                        resolvedClaims,
                        tc.getError(),
                        errorDescription,
                        resolvedRedirectUrl,
                        action
                );

                // If executing within a subflow, pop call stack and return to parent flow
                if (activeContext.hasCallStack()) {
                    StackFrame frame = activeContext.peekFrame();
                    activeContext = activeContext.withPoppedFrame();

                    FlowDefinition parentFlow = flowRegistry.getFlow(frame.getFlowId())
                            .orElseThrow(() -> new IllegalStateException("Parent flow not found in registry: " + frame.getFlowId()));
                    StateDefinition parentState = parentFlow.getState(frame.getReturnStateId());
                    if (parentState == null) {
                        throw new IllegalStateException("Return state '" + frame.getReturnStateId() + "' not found in parent flow: " + parentFlow.getId());
                    }

                    activeFlow = parentFlow;
                    activeContext = activeContext.withCurrentFlowId(activeFlow.getId());
                    currentStateId = parentState.getId();

                    // Provide subflow outcome & claims to parent transition bindings
                    Map<String, Object> subflowResult = new LinkedHashMap<>();
                    subflowResult.put("status", tc.getStatus());
                    subflowResult.put("claims", resolvedClaims);
                    if (tc.getError() != null) {
                        subflowResult.put("error", tc.getError());
                    }
                    if (errorDescription != null) {
                        subflowResult.put("errorDescription", errorDescription);
                    }
                    if (resolvedRedirectUrl != null) {
                        subflowResult.put("redirectUrl", resolvedRedirectUrl);
                    }
                    bindings.put("subflow", subflowResult);
                    bindings.put("outcome", tc.getStatus());
                    bindings.put("results", Map.of("subflow", subflowResult));

                    // Evaluate transitions on the parent calling state
                    TransitionDefinition nextTransition = findMatchingSubflowTransition(parentState, tc.getStatus(), bindings);
                    if (nextTransition != null) {
                        activeContext = applyContextUpdates(activeContext, nextTransition.getContextUpdates(), bindings);
                        currentStateId = nextTransition.getTarget();
                        activeContext = activeContext.withBreadcrumb(currentStateId);
                        currentState = activeFlow.getState(currentStateId);
                        bindings.put("context", activeContext.getData());
                        bindings.put("data", activeContext.getData());
                        continue;
                    } else {
                        planBuilder.checkpoint(new Checkpoint(activeFlow.getId(), currentStateId, List.of("unmatched_subflow_transition")));
                        break;
                    }
                } else {
                    // Top-level terminal outcome
                    planBuilder.terminal(termResult);
                    break;
                }
            }

            // Accumulate Backend Steps
            for (CommandDefinition cmd : currentState.getBackendCommands()) {
                Map<String, Object> resolvedPayload = celEvaluator.resolveTemplateMap(cmd.getPayload(), bindings);
                planBuilder.addBackendStep(new BackendStep(cmd.getId(), cmd.getService(), resolvedPayload));
            }

            // Accumulate Frontend Steps
            for (FrontendSchemaDefinition schemaDef : currentState.getFrontendSchemas()) {
                String title = celEvaluator.resolveTemplateString(schemaDef.getTitle(), bindings);
                String desc = celEvaluator.resolveTemplateString(schemaDef.getDescription(), bindings);
                Map<String, Object> data = celEvaluator.resolveTemplateMap(schemaDef.getData(), bindings);
                planBuilder.addFrontendStep(new FrontendStep(
                        schemaDef.getScreenId(),
                        title,
                        desc,
                        schemaDef.getJsonSchema(),
                        schemaDef.getUiSchema(),
                        data,
                        Collections.emptyList()
                ));
            }

            // Determine if we must yield a Checkpoint ("Come Back To Me")
            boolean hasFrontend = !currentState.getFrontendSchemas().isEmpty();
            boolean hasBackend = !currentState.getBackendCommands().isEmpty();
            boolean forceCheckpoint = currentState.isForceCheckpoint();
            boolean requiresPendingResults = transitionsRequireResults(currentState);

            if (forceCheckpoint || hasFrontend || (hasBackend && requiresPendingResults) || currentState.getTransitions().isEmpty()) {
                List<String> expected = new ArrayList<>();
                if (hasFrontend) expected.add("input");
                if (hasBackend) expected.add("results");
                planBuilder.checkpoint(new Checkpoint(activeFlow.getId(), currentStateId, expected));
                break;
            }

            // Otherwise, evaluate transitions immediately without external input
            TransitionDefinition nextTransition = findMatchingTransition(currentState, activeEvent, bindings);
            if (nextTransition != null) {
                activeContext = applyContextUpdates(activeContext, nextTransition.getContextUpdates(), bindings);
                currentStateId = nextTransition.getTarget();
                activeContext = activeContext.withBreadcrumb(currentStateId);
                currentState = activeFlow.getState(currentStateId);
                bindings.put("context", activeContext.getData());
                bindings.put("data", activeContext.getData());
            } else {
                planBuilder.checkpoint(new Checkpoint(activeFlow.getId(), currentStateId, List.of("unmatched_transition")));
                break;
            }
        }

        return planBuilder.updatedContext(activeContext).build();
    }

    /**
     * Simulates the complete execution trajectory of a flow from start to finish given initial context
     * (data and client configuration) and a set of mock decisions (command results and user inputs).
     *
     * @param flowId Registered flow ID
     * @param initialContext Initial session data and client configuration
     * @param decisions Mock decisions/outcomes keyed by command ID, screen ID, state ID, or 'results'/'input' maps
     * @return Full flow simulation result with execution path, all emitted commands/screens, and visual trace
     */
    public FlowSimulation simulate(String flowId, SessionContext initialContext, Map<String, Object> decisions) {
        FlowDefinition flow = flowRegistry.getFlow(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found in registry: " + flowId));
        return simulate(flow, initialContext, decisions);
    }

    /**
     * Simulates the complete execution trajectory of a flow from start to finish given initial context
     * (data and client configuration) and a set of mock decisions (command results and user inputs).
     *
     * @param flow Flow definition to simulate
     * @param initialContext Initial session data and client configuration
     * @param decisions Mock decisions/outcomes keyed by command ID, screen ID, state ID, or 'results'/'input' maps
     * @return Full flow simulation result with execution path, all emitted commands/screens, and visual trace
     */
    public FlowSimulation simulate(FlowDefinition flow, SessionContext initialContext, Map<String, Object> decisions) {
        if (flow == null && (initialContext == null || initialContext.getCurrentFlowId() == null)) {
            throw new IllegalArgumentException("flow must not be null");
        }
        if (flow != null) {
            flowRegistry.register(flow);
        }

        SessionContext activeContext = initialContext != null ? initialContext : new SessionContext();
        FlowDefinition rootFlow = flow;
        if (rootFlow == null && activeContext.getCurrentFlowId() != null) {
            rootFlow = flowRegistry.getFlow(activeContext.getCurrentFlowId()).orElse(null);
        }
        if (rootFlow == null) {
            throw new IllegalStateException("Active flow could not be resolved");
        }

        FlowDefinition activeFlow = rootFlow;
        Map<String, Object> mockDecisions = decisions != null ? decisions : Collections.emptyMap();
        List<SimulationStep> recordedSteps = new ArrayList<>();
        int maxSteps = 100;
        int stepNumber = 0;

        String currentStateId = activeFlow.getInitialState();
        activeContext = activeContext.withCurrentFlowId(activeFlow.getId()).withBreadcrumb(currentStateId);

        Map<String, Object> accumulatedResults = new LinkedHashMap<>();
        Map<String, Object> accumulatedInput = new LinkedHashMap<>(activeContext.getInput());

        if (mockDecisions.get("results") instanceof Map<?, ?> resMap) {
            resMap.forEach((k, v) -> accumulatedResults.put(String.valueOf(k), v));
        }
        if (mockDecisions.get("input") instanceof Map<?, ?> inMap) {
            inMap.forEach((k, v) -> accumulatedInput.put(String.valueOf(k), v));
        }

        TerminalResult terminalResult = null;
        Checkpoint pausedCheckpoint = null;

        while (currentStateId != null && stepNumber < maxSteps) {
            stepNumber++;
            StateDefinition currentState = activeFlow.getState(currentStateId);
            if (currentState == null) {
                throw new IllegalStateException("Unknown state in flow '" + activeFlow.getId() + "': " + currentStateId);
            }

            Map<String, Object> bindings = new HashMap<>();
            bindings.put("context", activeContext.getData());
            bindings.put("data", activeContext.getData());
            bindings.put("config", activeContext.getConfig());
            bindings.put("results", accumulatedResults);
            bindings.put("input", accumulatedInput);
            bindings.put("event", Collections.emptyMap());

            // Handle SUBFLOW delegation
            if (currentState.getType() == StateType.SUBFLOW) {
                String subflowId = currentState.getSubflow();
                if (subflowId == null || subflowId.isBlank()) {
                    throw new IllegalStateException("State '" + currentStateId + "' of type SUBFLOW must specify a 'subflow' identifier");
                }
                FlowDefinition childFlow = flowRegistry.getFlow(subflowId)
                        .orElseThrow(() -> new IllegalStateException("Subflow '" + subflowId + "' not found in registry: " + subflowId));

                recordedSteps.add(new SimulationStep(
                        stepNumber,
                        currentStateId,
                        currentState.getType().name(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        "subflow:" + subflowId,
                        activeContext.getData()
                ));

                activeContext = activeContext.withPushedFrame(new StackFrame(activeFlow.getId(), currentStateId));
                activeFlow = childFlow;
                activeContext = activeContext.withCurrentFlowId(activeFlow.getId());
                currentStateId = activeFlow.getInitialState();
                activeContext = activeContext.withBreadcrumb(currentStateId);
                continue;
            }

            // Resolve backend commands for this state
            List<BackendStep> backendSteps = new ArrayList<>();
            for (CommandDefinition cmd : currentState.getBackendCommands()) {
                Map<String, Object> resolvedPayload = celEvaluator.resolveTemplateMap(cmd.getPayload(), bindings);
                backendSteps.add(new BackendStep(cmd.getId(), cmd.getService(), resolvedPayload));

                if (mockDecisions.containsKey(cmd.getId())) {
                    accumulatedResults.put(cmd.getId(), mockDecisions.get(cmd.getId()));
                }
            }

            // Resolve frontend screens for this state
            List<FrontendStep> frontendSteps = new ArrayList<>();
            for (FrontendSchemaDefinition schemaDef : currentState.getFrontendSchemas()) {
                String title = celEvaluator.resolveTemplateString(schemaDef.getTitle(), bindings);
                String desc = celEvaluator.resolveTemplateString(schemaDef.getDescription(), bindings);
                Map<String, Object> data = celEvaluator.resolveTemplateMap(schemaDef.getData(), bindings);
                frontendSteps.add(new FrontendStep(
                        schemaDef.getScreenId(),
                        title,
                        desc,
                        schemaDef.getJsonSchema(),
                        schemaDef.getUiSchema(),
                        data,
                        Collections.emptyList()
                ));

                if (mockDecisions.containsKey(schemaDef.getScreenId())) {
                    Object screenInput = mockDecisions.get(schemaDef.getScreenId());
                    if (screenInput instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> accumulatedInput.put(String.valueOf(k), v));
                    } else {
                        accumulatedInput.put(schemaDef.getScreenId(), screenInput);
                    }
                }

                Set<String> declared = extractDeclaredProperties(List.of(schemaDef));
                for (String prop : declared) {
                    if (mockDecisions.containsKey(prop)) {
                        accumulatedInput.put(prop, mockDecisions.get(prop));
                    }
                }
            }

            if (mockDecisions.containsKey(currentStateId)) {
                Object stateDecision = mockDecisions.get(currentStateId);
                if (stateDecision instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> {
                        accumulatedInput.put(String.valueOf(k), v);
                        accumulatedResults.put(String.valueOf(k), v);
                    });
                } else {
                    accumulatedResults.put(currentStateId, stateDecision);
                }
            }

            bindings.put("results", accumulatedResults);
            bindings.put("input", accumulatedInput);

            // Merge declared schema properties from input into activeContext data
            if (!frontendSteps.isEmpty()) {
                Set<String> declaredProperties = extractDeclaredProperties(currentState.getFrontendSchemas());
                Map<String, Object> safeUpdates = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : accumulatedInput.entrySet()) {
                    String key = entry.getKey();
                    if (declaredProperties.isEmpty() || declaredProperties.contains(key)) {
                        if (!activeContext.getData().containsKey(key)) {
                            safeUpdates.put(key, entry.getValue());
                        }
                    }
                }
                if (!safeUpdates.isEmpty()) {
                    activeContext = activeContext.withAll(safeUpdates);
                    bindings.put("context", activeContext.getData());
                    bindings.put("data", activeContext.getData());
                }
            }

            // Check if TERMINAL
            if (currentState.getType() == StateType.TERMINAL || currentState.getTerminalConfig() != null) {
                TerminalConfig tc = currentState.getTerminalConfig() != null
                        ? currentState.getTerminalConfig()
                        : new TerminalConfig("SUCCESS", Collections.emptyMap(), null);

                Map<String, Object> resolvedClaims = celEvaluator.resolveTemplateMap(tc.getClaims(), bindings);
                Object resolvedRedirect = celEvaluator.resolveTemplateValue(tc.getRedirectUrl(), bindings);
                String resolvedRedirectUrl = resolvedRedirect != null ? resolvedRedirect.toString() : null;
                Object resolvedErrorDesc = celEvaluator.resolveTemplateValue(tc.getErrorDescription(), bindings);
                String errorDescription = resolvedErrorDesc != null ? resolvedErrorDesc.toString() : null;

                String action = tc.getAction();
                if (action == null || action.isBlank()) {
                    if (resolvedRedirectUrl != null && !resolvedRedirectUrl.isBlank()) {
                        action = TerminalResult.ACTION_REDIRECT;
                    } else if (!frontendSteps.isEmpty()) {
                        action = TerminalResult.ACTION_UI;
                    } else {
                        action = TerminalResult.ACTION_COMPLETE;
                    }
                }

                TerminalResult stepTerminal = new TerminalResult(
                        tc.getStatus(),
                        resolvedClaims,
                        tc.getError(),
                        errorDescription,
                        resolvedRedirectUrl,
                        action
                );

                if (activeContext.hasCallStack()) {
                    recordedSteps.add(new SimulationStep(
                            stepNumber,
                            currentStateId,
                            "TERMINAL",
                            backendSteps,
                            frontendSteps,
                            "return_to_parent",
                            activeContext.getData()
                    ));

                    StackFrame frame = activeContext.peekFrame();
                    activeContext = activeContext.withPoppedFrame();

                    FlowDefinition parentFlow = flowRegistry.getFlow(frame.getFlowId())
                            .orElseThrow(() -> new IllegalStateException("Parent flow not found in registry: " + frame.getFlowId()));
                    StateDefinition parentState = parentFlow.getState(frame.getReturnStateId());

                    activeFlow = parentFlow;
                    activeContext = activeContext.withCurrentFlowId(activeFlow.getId());
                    currentStateId = parentState.getId();

                    Map<String, Object> subflowResult = new LinkedHashMap<>();
                    subflowResult.put("status", tc.getStatus());
                    subflowResult.put("claims", resolvedClaims);
                    if (tc.getError() != null) {
                        subflowResult.put("error", tc.getError());
                    }
                    if (errorDescription != null) {
                        subflowResult.put("errorDescription", errorDescription);
                    }
                    if (resolvedRedirectUrl != null) {
                        subflowResult.put("redirectUrl", resolvedRedirectUrl);
                    }
                    bindings.put("subflow", subflowResult);
                    bindings.put("outcome", tc.getStatus());
                    accumulatedResults.put("subflow", subflowResult);
                    bindings.put("results", accumulatedResults);

                    TransitionDefinition nextTransition = findMatchingSubflowTransition(parentState, tc.getStatus(), bindings);
                    if (nextTransition != null) {
                        activeContext = applyContextUpdates(activeContext, nextTransition.getContextUpdates(), bindings);
                        currentStateId = nextTransition.getTarget();
                        activeContext = activeContext.withBreadcrumb(currentStateId);
                        continue;
                    } else {
                        pausedCheckpoint = new Checkpoint(activeFlow.getId(), currentStateId, List.of("unmatched_subflow_transition"));
                        break;
                    }
                } else {
                    terminalResult = stepTerminal;
                    recordedSteps.add(new SimulationStep(
                            stepNumber,
                            currentStateId,
                            "TERMINAL",
                            backendSteps,
                            frontendSteps,
                            null,
                            activeContext.getData()
                    ));
                    break;
                }
            }

            boolean hasFrontend = !currentState.getFrontendSchemas().isEmpty();
            boolean hasBackend = !currentState.getBackendCommands().isEmpty();
            boolean requiresPendingResults = transitionsRequireResults(currentState);

            if (hasFrontend && !hasProvidedInput(currentState, mockDecisions, accumulatedInput)) {
                recordedSteps.add(new SimulationStep(
                        stepNumber,
                        currentStateId,
                        currentState.getType().name(),
                        backendSteps,
                        frontendSteps,
                        null,
                        activeContext.getData()
                ));
                pausedCheckpoint = new Checkpoint(activeFlow.getId(), currentStateId, List.of("input"));
                break;
            }

            if (requiresPendingResults && !hasRequiredResults(currentState, accumulatedResults)) {
                recordedSteps.add(new SimulationStep(
                        stepNumber,
                        currentStateId,
                        currentState.getType().name(),
                        backendSteps,
                        frontendSteps,
                        null,
                        activeContext.getData()
                ));
                pausedCheckpoint = new Checkpoint(activeFlow.getId(), currentStateId, List.of("results"));
                break;
            }

            Event simEvent = Event.start();
            if (hasFrontend) {
                simEvent = Event.submit(accumulatedInput);
            } else if (hasBackend) {
                simEvent = Event.resume(accumulatedResults);
            }

            TransitionDefinition nextTransition = findMatchingTransition(currentState, simEvent, bindings);
            if (nextTransition != null) {
                recordedSteps.add(new SimulationStep(
                        stepNumber,
                        currentStateId,
                        currentState.getType().name(),
                        backendSteps,
                        frontendSteps,
                        nextTransition.getTarget(),
                        activeContext.getData()
                ));

                activeContext = applyContextUpdates(activeContext, nextTransition.getContextUpdates(), bindings);
                currentStateId = nextTransition.getTarget();
                activeContext = activeContext.withBreadcrumb(currentStateId);
            } else {
                recordedSteps.add(new SimulationStep(
                        stepNumber,
                        currentStateId,
                        currentState.getType().name(),
                        backendSteps,
                        frontendSteps,
                        null,
                        activeContext.getData()
                ));
                pausedCheckpoint = new Checkpoint(activeFlow.getId(), currentStateId, List.of("unmatched_transition"));
                break;
            }
        }

        boolean completed = terminalResult != null;
        return new FlowSimulation(rootFlow.getId(), recordedSteps, activeContext, terminalResult, pausedCheckpoint, completed);
    }

    private TransitionDefinition findMatchingSubflowTransition(
            StateDefinition parentState,
            String outcomeStatus,
            Map<String, Object> bindings) {

        TransitionDefinition defaultTransition = null;

        for (TransitionDefinition transition : parentState.getTransitions()) {
            if (transition.isDefault()) {
                defaultTransition = transition;
                continue;
            }

            // 1. Explicit outcome match (e.g. outcome: SUCCESS or outcome: DENIED)
            if (transition.getOutcome() != null && !transition.getOutcome().isBlank()) {
                if (transition.getOutcome().equalsIgnoreCase(outcomeStatus)) {
                    return transition;
                }
                continue;
            }

            // 2. CEL condition match (e.g. if: "subflow.status == 'SUCCESS'")
            if (transition.getCondition() != null && !transition.getCondition().isBlank()) {
                boolean matches = celEvaluator.evaluateCondition(transition.getCondition(), bindings);
                if (matches) {
                    return transition;
                }
                continue;
            }
        }

        return defaultTransition;
    }

    private TransitionDefinition findMatchingTransition(
            StateDefinition state,
            Event event,
            Map<String, Object> bindings) {

        TransitionDefinition defaultTransition = null;

        for (TransitionDefinition transition : state.getTransitions()) {
            if (transition.isDefault()) {
                defaultTransition = transition;
                continue;
            }

            // Check event type match if specified
            if (transition.getEvent() != null && !transition.getEvent().isBlank()) {
                if (!transition.getEvent().equalsIgnoreCase(event.getType())) {
                    continue;
                }
            }

            // Check condition if specified
            if (transition.getCondition() != null && !transition.getCondition().isBlank()) {
                boolean matches = celEvaluator.evaluateCondition(transition.getCondition(), bindings);
                if (matches) {
                    return transition;
                }
            } else if (transition.getEvent() != null && transition.getEvent().equalsIgnoreCase(event.getType())) {
                return transition;
            }
        }

        return defaultTransition;
    }

    private boolean transitionsRequireResults(StateDefinition state) {
        for (TransitionDefinition t : state.getTransitions()) {
            if (t.getCondition() != null && t.getCondition().contains("results.")) {
                return true;
            }
        }
        return false;
    }

    private SessionContext applyContextUpdates(
            SessionContext context,
            Map<String, String> updates,
            Map<String, Object> bindings) {
        if (updates == null || updates.isEmpty()) {
            return context;
        }

        SessionContext current = context;
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            Object evaluated = celEvaluator.evaluate(entry.getValue(), bindings);
            current = current.withValue(entry.getKey(), evaluated);
        }
        return current;
    }

    private Set<String> extractDeclaredProperties(List<FrontendSchemaDefinition> schemas) {
        Set<String> declared = new HashSet<>();
        if (schemas == null) return declared;
        for (FrontendSchemaDefinition schemaDef : schemas) {
            if (schemaDef.getJsonSchema() != null && schemaDef.getJsonSchema().has("properties")) {
                tools.jackson.databind.JsonNode propertiesNode = schemaDef.getJsonSchema().get("properties");
                if (propertiesNode != null && propertiesNode.isObject()) {
                    declared.addAll(propertiesNode.propertyNames());
                }
            }
        }
        return declared;
    }

    private boolean hasProvidedInput(StateDefinition state, Map<String, Object> decisions, Map<String, Object> accumulatedInput) {
        if (decisions.containsKey(state.getId()) || decisions.containsKey("input")) {
            return true;
        }
        for (FrontendSchemaDefinition schema : state.getFrontendSchemas()) {
            if (decisions.containsKey(schema.getScreenId()) || accumulatedInput.containsKey(schema.getScreenId())) {
                return true;
            }
            if (schema.getJsonSchema() != null && schema.getJsonSchema().has("properties")) {
                tools.jackson.databind.JsonNode props = schema.getJsonSchema().get("properties");
                if (props != null && props.isObject()) {
                    for (String propName : props.propertyNames()) {
                        if (accumulatedInput.containsKey(propName) || decisions.containsKey(propName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean hasRequiredResults(StateDefinition state, Map<String, Object> accumulatedResults) {
        for (TransitionDefinition t : state.getTransitions()) {
            if (t.getCondition() != null && t.getCondition().contains("results.")) {
                for (CommandDefinition cmd : state.getBackendCommands()) {
                    if (t.getCondition().contains("results." + cmd.getId()) && !accumulatedResults.containsKey(cmd.getId())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
