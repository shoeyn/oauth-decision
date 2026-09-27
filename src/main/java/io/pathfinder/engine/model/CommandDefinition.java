package io.pathfinder.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class CommandDefinition {
    private final String id;
    private final String service;
    private final Map<String, Object> payload;

    @JsonCreator
    public CommandDefinition(
            @JsonProperty("id") String id,
            @JsonProperty("service") String service,
            @JsonProperty("payload") Map<String, Object> payload) {
        this.id = id;
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.payload = payload != null ? Collections.unmodifiableMap(payload) : Collections.emptyMap();
    }

    public String getId() {
        return id;
    }

    public String getService() {
        return service;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
