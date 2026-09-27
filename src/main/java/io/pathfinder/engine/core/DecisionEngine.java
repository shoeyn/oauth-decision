package io.pathfinder.engine.core;

import io.pathfinder.engine.model.*;
import io.pathfinder.engine.runtime.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

public class DecisionEngine {
    private final CelExpressionEvaluator celEvaluator;
    private final JsonSchemaInputValidator schemaValidator;

    public DecisionEngine() {
        this(new CelExpressionEvaluator(), new JsonSchemaInputValidator(JsonMapper.builder().build()));
    }

    public DecisionEngine(CelExpressionEvaluator celEvaluator, JsonSchemaInputValidator schemaValidator) {
        this.celEvaluator = celEvaluator != null ? celEvaluator : new CelExpressionEvaluator();
        this.schemaValidator = schemaValidator != null ? schemaValidator : new JsonSchemaInputValidator(JsonMapper.builder().build());
    }

    public ExecutionPlan evaluate(FlowDefinition flow, String currentStateId, SessionContext context, Event event) {
        Objects.requireNonNull(flow, "flow must not be null");

        SessionContext activeContext = context != null ? context : new SessionContext();
        Event activeEvent = event != null ? event : Event.start();

        // 1. Determine starting state
        boolean isInitialTurn = (currentStateId == null || currentStateId.isBlank());
        if (isInitialTurn) {
            currentStateId = flow.getInitialState();
            activeContext = activeContext.withBreadcrumb(currentStateId);
        }

        StateDefinition currentState = flow.getState(currentStateId);
        if (currentState == null) {
            throw new IllegalArgumentException("Unknown state in flow: " + currentStateId);
        }

        // 2. If this is a SUBMIT event from a frontend step, validate input against JSON Schema first
        if (Event.TYPE_SUBMIT.equalsIgnoreCase(activeEvent.getType()) && !currentState.getFrontendSchemas().isEmpty()) {
            for (FrontendSchemaDefinition schemaDef : currentState.getFrontendSchemas()) {
                if (schemaDef.getJsonSchema() != null) {
                    List<String> errors = schemaValidator.validate(schemaDef.getJsonSchema(), activeEvent.getPayload());
                    if (!errors.isEmpty()) {
                        // Return current state with validation errors; do not transition
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
                                .checkpoint(new Checkpoint(currentStateId, List.of("input")))
                                .updatedContext(activeContext)
                                .build();
                    }
                }
            }
            // If validated, merge submitted input into context
            activeContext = activeContext.withAll(activeEvent.getPayload());
        }

        // 3. Build evaluation bindings
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("context", activeContext.getData());
        bindings.put("event", activeEvent.getPayload());
        if (Event.TYPE_RESUME.equalsIgnoreCase(activeEvent.getType())) {
            bindings.put("results", activeEvent.getPayload());
        } else if (Event.TYPE_SUBMIT.equalsIgnoreCase(activeEvent.getType())) {
            bindings.put("input", activeEvent.getPayload());
            bindings.put("results", Collections.emptyMap());
        } else {
            bindings.put("results", Collections.emptyMap());
            bindings.put("input", Collections.emptyMap());
        }

        // 4. If we are resuming or submitting on an existing state, evaluate its transition first
        if (!isInitialTurn) {
            TransitionDefinition matched = findMatchingTransition(currentState, activeEvent, bindings);
            if (matched != null) {
                activeContext = applyContextUpdates(activeContext, matched.getContextUpdates(), bindings);
                currentStateId = matched.getTarget();
                activeContext = activeContext.withBreadcrumb(currentStateId);
                currentState = flow.getState(currentStateId);
                if (currentState == null) {
                    throw new IllegalStateException("Transition target state does not exist: " + matched.getTarget());
                }
                // Refresh bindings after context update
                bindings.put("context", activeContext.getData());
            }
        }

        // 5. Look-ahead accumulation loop
        ExecutionPlan.Builder planBuilder = ExecutionPlan.builder();
        Set<String> visitedInThisTurn = new HashSet<>();

        while (currentState != null) {
            planBuilder.currentState(currentStateId);

            // Avoid infinite loops in cycle graphs without external checkpoints
            if (!visitedInThisTurn.add(currentStateId)) {
                planBuilder.checkpoint(new Checkpoint(currentStateId, List.of("cycle_detected")));
                break;
            }

            // Check if TERMINAL
            if (currentState.getType() == StateType.TERMINAL || currentState.getTerminalConfig() != null) {
                TerminalConfig tc = currentState.getTerminalConfig() != null
                        ? currentState.getTerminalConfig()
                        : new TerminalConfig("SUCCESS", Collections.emptyMap(), null);

                Map<String, Object> resolvedClaims = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : tc.getClaims().entrySet()) {
                    resolvedClaims.put(entry.getKey(), celEvaluator.resolveTemplateValue(entry.getValue(), bindings));
                }

                planBuilder.terminal(new TerminalResult(tc.getStatus(), resolvedClaims, tc.getError()));
                break;
            }

            // Accumulate Backend Steps
            for (CommandDefinition cmd : currentState.getBackendCommands()) {
                Map<String, Object> resolvedPayload = (Map<String, Object>) celEvaluator.resolveTemplateValue(cmd.getPayload(), bindings);
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
                // Must halt and yield to host/frontend
                List<String> expected = new ArrayList<>();
                if (hasFrontend) expected.add("input");
                if (hasBackend) expected.add("results");
                planBuilder.checkpoint(new Checkpoint(currentStateId, expected));
                break;
            }

            // Otherwise, we can evaluate transitions right away without external input
            TransitionDefinition nextTransition = findMatchingTransition(currentState, activeEvent, bindings);
            if (nextTransition != null) {
                activeContext = applyContextUpdates(activeContext, nextTransition.getContextUpdates(), bindings);
                currentStateId = nextTransition.getTarget();
                activeContext = activeContext.withBreadcrumb(currentStateId);
                currentState = flow.getState(currentStateId);
                bindings.put("context", activeContext.getData());
            } else {
                // No transition matched, halt at current state
                planBuilder.checkpoint(new Checkpoint(currentStateId, List.of("unmatched_transition")));
                break;
            }
        }

        return planBuilder.updatedContext(activeContext).build();
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
                // Event matches and no condition specified
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
}
