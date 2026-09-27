package io.pathfinder.engine.core;

import io.pathfinder.engine.model.*;
import io.pathfinder.engine.registry.FlowRegistry;
import io.pathfinder.engine.registry.InMemoryFlowRegistry;
import io.pathfinder.engine.runtime.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

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
            for (FrontendSchemaDefinition schemaDef : currentState.getFrontendSchemas()) {
                if (schemaDef.getJsonSchema() != null) {
                    List<String> errors = schemaValidator.validate(schemaDef.getJsonSchema(), activeEvent.getPayload());
                    if (!errors.isEmpty()) {
                        FrontendStep failedStep = new FrontendStep(
                                schemaDef.getScreenId(),
                                schemaDef.getTitle(),
                                schemaDef.getDescription(),
                                schemaDef.getJsonSchema(),
                                schemaDef.getUiSchema(),
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
                continue;
            }

            // Check if TERMINAL
            if (currentState.getType() == StateType.TERMINAL || currentState.getTerminalConfig() != null) {
                TerminalConfig tc = currentState.getTerminalConfig() != null
                        ? currentState.getTerminalConfig()
                        : new TerminalConfig("SUCCESS", Collections.emptyMap(), null);

                Map<String, Object> resolvedClaims = celEvaluator.resolveTemplateMap(tc.getClaims(), bindings);

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
                        continue;
                    } else {
                        planBuilder.checkpoint(new Checkpoint(activeFlow.getId(), currentStateId, List.of("unmatched_subflow_transition")));
                        break;
                    }
                } else {
                    // Top-level terminal outcome
                    planBuilder.terminal(new TerminalResult(tc.getStatus(), resolvedClaims, tc.getError()));
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
                planBuilder.addFrontendStep(new FrontendStep(
                        schemaDef.getScreenId(),
                        schemaDef.getTitle(),
                        schemaDef.getDescription(),
                        schemaDef.getJsonSchema(),
                        schemaDef.getUiSchema(),
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
            } else {
                planBuilder.checkpoint(new Checkpoint(activeFlow.getId(), currentStateId, List.of("unmatched_transition")));
                break;
            }
        }

        return planBuilder.updatedContext(activeContext).build();
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
}
