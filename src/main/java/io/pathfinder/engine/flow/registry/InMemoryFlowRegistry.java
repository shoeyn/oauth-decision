package io.pathfinder.engine.flow.registry;

import io.pathfinder.engine.flow.model.FlowDefinition;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
