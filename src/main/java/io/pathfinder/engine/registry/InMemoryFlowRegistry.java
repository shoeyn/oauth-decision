package io.pathfinder.engine.registry;

import io.pathfinder.engine.model.FlowDefinition;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory thread-safe implementation of {@link FlowRegistry}.
 */
public class InMemoryFlowRegistry implements FlowRegistry {
    private final Map<String, FlowDefinition> flows = new ConcurrentHashMap<>();

    public InMemoryFlowRegistry() {
    }

    public InMemoryFlowRegistry(FlowDefinition... flows) {
        if (flows != null) {
            for (FlowDefinition f : flows) {
                register(f);
            }
        }
    }

    public InMemoryFlowRegistry(Collection<FlowDefinition> flows) {
        if (flows != null) {
            flows.forEach(this::register);
        }
    }

    @Override
    public void register(FlowDefinition flow) {
        Objects.requireNonNull(flow, "flow must not be null");
        flows.put(flow.getId(), flow);
    }

    @Override
    public Optional<FlowDefinition> getFlow(String flowId) {
        if (flowId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(flows.get(flowId));
    }

    @Override
    public Collection<FlowDefinition> getAllFlows() {
        return Collections.unmodifiableCollection(flows.values());
    }
}
