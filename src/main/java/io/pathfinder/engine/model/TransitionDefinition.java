package io.pathfinder.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class TransitionDefinition {
    private final String event;
    private final String condition;
    private final String target;
    private final Map<String, String> contextUpdates;
    private final boolean isDefault;
    private final String outcome;

    public TransitionDefinition(
            String event,
            String condition,
            String target,
            Map<String, String> contextUpdates,
            Boolean isDefault) {
        this(event, condition, target, contextUpdates, isDefault, null);
    }

    @JsonCreator
    public TransitionDefinition(
            @JsonProperty("event") String event,
            @JsonProperty("if") String condition,
            @JsonProperty("target") String target,
            @JsonProperty("contextUpdates") Map<String, String> contextUpdates,
            @JsonProperty("default") Boolean isDefault,
            @JsonProperty("outcome") String outcome) {
        this.event = event;
        this.condition = condition;
        this.target = Objects.requireNonNull(target, "target state must not be null");
        this.contextUpdates = contextUpdates != null ? Collections.unmodifiableMap(contextUpdates) : Collections.emptyMap();
        this.isDefault = Boolean.TRUE.equals(isDefault);
        this.outcome = outcome;
    }

    public String getEvent() {
        return event;
    }

    public String getCondition() {
        return condition;
    }

    public String getTarget() {
        return target;
    }

    public Map<String, String> getContextUpdates() {
        return contextUpdates;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public String getOutcome() {
        return outcome;
    }
}
