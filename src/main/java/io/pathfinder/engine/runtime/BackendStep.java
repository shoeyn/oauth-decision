package io.pathfinder.engine.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class BackendStep {
    private final String stepId;
    private final String service;
    private final Map<String, Object> payload;

    public BackendStep(String stepId, String service, Map<String, Object> payload) {
        this.stepId = stepId;
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.payload = payload != null ? Collections.unmodifiableMap(payload) : Collections.emptyMap();
    }

    public String getStepId() {
        return stepId;
    }

    public String getService() {
        return service;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
