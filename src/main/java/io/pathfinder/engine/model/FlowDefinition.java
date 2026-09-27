package io.pathfinder.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class FlowDefinition {
    private final String id;
    private final String version;
    private final String name;
    private final String initialState;
    private final Map<String, StateDefinition> states;

    @JsonCreator
    public FlowDefinition(
            @JsonProperty("id") String id,
            @JsonProperty("version") String version,
            @JsonProperty("name") String name,
            @JsonProperty("initialState") String initialState,
            @JsonProperty("states") Map<String, StateDefinition> states) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.version = version != null ? version : "1.0.0";
        this.name = name != null ? name : id;
        this.initialState = Objects.requireNonNull(initialState, "initialState must not be null");

        if (states != null) {
            Map<String, StateDefinition> copy = new LinkedHashMap<>();
            states.forEach((k, v) -> {
                // Ensure state id is populated even if not in the state object itself
                StateDefinition populated = v.getId() == null
                        ? new StateDefinition(k, v.getType(), null, v.getBackendCommands(),
                        null, v.getFrontendSchemas(), v.getTerminalConfig(), v.getTransitions(), v.isForceCheckpoint())
                        : v;
                copy.put(k, populated);
            });
            this.states = Collections.unmodifiableMap(copy);
        } else {
            this.states = Collections.emptyMap();
        }
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getInitialState() {
        return initialState;
    }

    public Map<String, StateDefinition> getStates() {
        return states;
    }

    public StateDefinition getState(String stateId) {
        return states.get(stateId);
    }
}
