package io.pathfinder.engine.flow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FlowDefinition {
    private final String id;
    private final String version;
    private final String name;
    private final String initialState;
    private final List<String> includes;
    private final Map<String, StateDefinition> states;

    public FlowDefinition(
            String id,
            String version,
            String name,
            String initialState,
            Map<String, StateDefinition> states) {
        this(id, version, name, initialState, states, Collections.emptyList());
    }

    @JsonCreator
    public FlowDefinition(
            @JsonProperty("id") String id,
            @JsonProperty("version") String version,
            @JsonProperty("name") String name,
            @JsonProperty("initialState") String initialState,
            @JsonProperty("states") Map<String, StateDefinition> states,
            @JsonProperty("includes") List<String> includes) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.version = version != null ? version : "1.0.0";
        this.name = name != null ? name : id;
        this.initialState = Objects.requireNonNull(initialState, "initialState must not be null");
        this.includes = includes != null ? Collections.unmodifiableList(new ArrayList<>(includes)) : Collections.emptyList();

        if (states != null) {
            Map<String, StateDefinition> copy = new LinkedHashMap<>();
            states.forEach((k, v) -> {
                // Ensure state id is populated even if not in the state object itself
                StateDefinition populated = v.getId() == null
                        ? new StateDefinition(k, v.getType(), null, v.getBackendCommands(),
                        null, v.getFrontendSchemas(), v.getTerminalConfig(), v.getTransitions(), v.isForceCheckpoint(), v.getSubflow(), v.getOnError(), v.getMaxAttempts())
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

    public List<String> getIncludes() {
        return includes;
    }

    public Map<String, StateDefinition> getStates() {
        return states;
    }

    public StateDefinition getState(String stateId) {
        return states.get(stateId);
    }
}
