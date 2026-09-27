package io.pathfinder.engine.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Checkpoint {
    public static final String ACTION_COME_BACK = "COME_BACK_TO_ME";

    private final String action;
    private final String flowId;
    private final String resumeState;
    private final List<String> expectedInputs;
    private final String correlationId;

    public Checkpoint(String resumeState, List<String> expectedInputs) {
        this(ACTION_COME_BACK, null, resumeState, expectedInputs, UUID.randomUUID().toString());
    }

    public Checkpoint(String action, String resumeState, List<String> expectedInputs, String correlationId) {
        this(action, null, resumeState, expectedInputs, correlationId);
    }

    public Checkpoint(String flowId, String resumeState, List<String> expectedInputs) {
        this(ACTION_COME_BACK, flowId, resumeState, expectedInputs, UUID.randomUUID().toString());
    }

    public Checkpoint(String action, String flowId, String resumeState, List<String> expectedInputs, String correlationId) {
        this.action = action != null ? action : ACTION_COME_BACK;
        this.flowId = flowId;
        this.resumeState = Objects.requireNonNull(resumeState, "resumeState must not be null");
        this.expectedInputs = expectedInputs != null ? Collections.unmodifiableList(expectedInputs) : Collections.emptyList();
        this.correlationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
    }

    public String getAction() {
        return action;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getResumeState() {
        return resumeState;
    }

    public List<String> getExpectedInputs() {
        return expectedInputs;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
