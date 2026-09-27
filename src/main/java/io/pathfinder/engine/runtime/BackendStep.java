package io.pathfinder.engine.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class BackendStep {
    private final String stepId;
    private final String service;
    private final Map<String, Object> payload;

    @JsonCreator
    public BackendStep(
            @JsonProperty("stepId") String stepId,
            @JsonProperty("service") String service,
            @JsonProperty("payload") Map<String, Object> payload) {
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

    @Override
    public String toString() {
        return "BackendStep{" +
                "stepId='" + stepId + '\'' +
                ", service='" + service + '\'' +
                ", payload=" + payload +
                '}';
    }
}
