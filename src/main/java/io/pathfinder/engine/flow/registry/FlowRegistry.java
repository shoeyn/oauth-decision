package io.pathfinder.engine.flow.registry;

import io.pathfinder.engine.flow.model.FlowDefinition;
import java.util.Collection;
import java.util.Optional;

public interface FlowRegistry {
    /**
     * Registers a flow definition.
     */
    void register(FlowDefinition flow);

    /**
     * Retrieves a flow definition by ID.
     */
    Optional<FlowDefinition> getFlow(String flowId);

    /**
     * Checks if a flow definition exists.
     */
    default boolean hasFlow(String flowId) {
        return getFlow(flowId).isPresent();
    }

    /**
     * Returns all registered flow definitions.
     */
    Collection<FlowDefinition> getAllFlows();
}
