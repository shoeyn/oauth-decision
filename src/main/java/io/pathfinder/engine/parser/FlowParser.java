package io.pathfinder.engine.parser;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.model.StateDefinition;
import io.pathfinder.engine.model.TransitionDefinition;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class FlowParser {
    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public FlowParser() {
        this.yamlMapper = YAMLMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.jsonMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public FlowDefinition parseYaml(String yaml) {
        FlowDefinition flow;
        try {
            flow = yamlMapper.readValue(yaml, FlowDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML flow definition: " + e.getMessage(), e);
        }
        validate(flow);
        return flow;
    }

    public FlowDefinition parseYaml(InputStream inputStream) {
        FlowDefinition flow;
        try {
            flow = yamlMapper.readValue(inputStream, FlowDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML flow definition from stream: " + e.getMessage(), e);
        }
        validate(flow);
        return flow;
    }

    public FlowDefinition parseYaml(File file) {
        FlowDefinition flow;
        try {
            flow = yamlMapper.readValue(file, FlowDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML flow definition from file: " + e.getMessage(), e);
        }
        validate(flow);
        return flow;
    }

    public FlowDefinition parseJson(String json) {
        FlowDefinition flow;
        try {
            flow = jsonMapper.readValue(json, FlowDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON flow definition: " + e.getMessage(), e);
        }
        validate(flow);
        return flow;
    }

    private void validate(FlowDefinition flow) {
        if (flow == null) {
            throw new IllegalArgumentException("Flow definition cannot be null");
        }
        if (flow.getId() == null || flow.getId().isBlank()) {
            throw new IllegalArgumentException("Flow definition must have an 'id'");
        }
        if (flow.getInitialState() == null || flow.getInitialState().isBlank()) {
            throw new IllegalArgumentException("Flow definition must specify an 'initialState'");
        }
        if (!flow.getStates().containsKey(flow.getInitialState())) {
            throw new IllegalArgumentException("Initial state '" + flow.getInitialState() + "' not found in states map");
        }

        List<String> errors = new ArrayList<>();
        for (StateDefinition state : flow.getStates().values()) {
            for (TransitionDefinition transition : state.getTransitions()) {
                if (transition.getTarget() == null || transition.getTarget().isBlank()) {
                    errors.add("Transition from state '" + state.getId() + "' has null or empty target");
                } else if (!flow.getStates().containsKey(transition.getTarget())) {
                    errors.add("Transition target '" + transition.getTarget() + "' from state '" + state.getId() + "' does not exist");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Flow validation failed: " + String.join("; ", errors));
        }
    }
}
