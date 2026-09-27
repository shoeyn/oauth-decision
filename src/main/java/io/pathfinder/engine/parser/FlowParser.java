package io.pathfinder.engine.parser;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.model.StateDefinition;
import io.pathfinder.engine.model.StateType;
import io.pathfinder.engine.model.TransitionDefinition;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.File;
import java.io.InputStream;
import java.util.*;

@SuppressWarnings("null")
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
        try {
            FlowDefinition flow = yamlMapper.readValue(yaml, FlowDefinition.class);
            flow = resolveIncludes(flow);
            validate(flow);
            return flow;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML flow definition: " + e.getMessage(), e);
        }
    }

    public FlowDefinition parseYaml(InputStream inputStream) {
        try {
            FlowDefinition flow = yamlMapper.readValue(inputStream, FlowDefinition.class);
            flow = resolveIncludes(flow);
            validate(flow);
            return flow;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML flow definition from stream: " + e.getMessage(), e);
        }
    }

    public FlowDefinition parseYaml(File file) {
        try {
            FlowDefinition flow = yamlMapper.readValue(file, FlowDefinition.class);
            flow = resolveIncludes(flow);
            validate(flow);
            return flow;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse YAML flow definition from file: " + e.getMessage(), e);
        }
    }

    public FlowDefinition parseJson(String json) {
        try {
            FlowDefinition flow = jsonMapper.readValue(json, FlowDefinition.class);
            flow = resolveIncludes(flow);
            validate(flow);
            return flow;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON flow definition: " + e.getMessage(), e);
        }
    }

    private FlowDefinition resolveIncludes(FlowDefinition flow) {
        if (flow == null || flow.getIncludes() == null || flow.getIncludes().isEmpty()) {
            return flow;
        }

        Map<String, StateDefinition> combinedStates = new LinkedHashMap<>();
        for (String includePath : flow.getIncludes()) {
            FlowDefinition included = loadIncludedFlow(includePath);
            if (included != null && included.getStates() != null) {
                combinedStates.putAll(included.getStates());
            }
        }

        // Master flow's explicit states take precedence over included states
        combinedStates.putAll(flow.getStates());

        return new FlowDefinition(
                flow.getId(),
                flow.getVersion(),
                flow.getName(),
                flow.getInitialState(),
                combinedStates,
                flow.getIncludes()
        );
    }

    private FlowDefinition loadIncludedFlow(String path) {
        // 1. Try classpath resource
        String resPath = path.startsWith("/") ? path : "/" + path;
        InputStream is = getClass().getResourceAsStream(resPath);
        if (is == null) {
            is = getClass().getResourceAsStream(path);
        }
        if (is != null) {
            return parseYaml(is);
        }

        // 2. Try file system
        File file = new File(path);
        if (file.exists() && file.isFile()) {
            return parseYaml(file);
        }

        throw new IllegalArgumentException("Unable to resolve flow include: " + path);
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
            if (state.getType() == StateType.SUBFLOW) {
                if (state.getSubflow() == null || state.getSubflow().isBlank()) {
                    errors.add("State '" + state.getId() + "' of type SUBFLOW must specify a 'subflow' identifier");
                }
            }
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
