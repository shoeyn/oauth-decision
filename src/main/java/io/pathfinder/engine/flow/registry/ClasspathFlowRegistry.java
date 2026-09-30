package io.pathfinder.engine.flow.registry;

import io.pathfinder.engine.flow.model.FlowDefinition;
import io.pathfinder.engine.flow.parser.FlowParser;
import java.io.InputStream;
import java.util.Collection;
import java.util.Optional;

public class ClasspathFlowRegistry implements FlowRegistry {
    private final InMemoryFlowRegistry delegate = new InMemoryFlowRegistry();
    private final FlowParser parser;

    public ClasspathFlowRegistry() {
        this(new FlowParser());
    }

    public ClasspathFlowRegistry(FlowParser parser) {
        this.parser = parser != null ? parser : new FlowParser();
    }

    /**
     * Loads and registers a flow definition from a classpath resource.
     *
     * @param resourcePath classpath path (e.g. "/flows/oauth_stepup_auth.yaml")
     * @return the parsed FlowDefinition
     */
    public FlowDefinition registerResource(String resourcePath) {
        InputStream is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found on classpath: " + resourcePath);
        }
        FlowDefinition flow = parser.parseYaml(is);
        register(flow);
        return flow;
    }

    public ClasspathFlowRegistry withResource(String resourcePath) {
        registerResource(resourcePath);
        return this;
    }

    @Override
    public void register(FlowDefinition flow) {
        delegate.register(flow);
    }

    @Override
    public Optional<FlowDefinition> getFlow(String flowId) {
        return delegate.getFlow(flowId);
    }

    @Override
    public Collection<FlowDefinition> getAllFlows() {
        return delegate.getAllFlows();
    }
}
