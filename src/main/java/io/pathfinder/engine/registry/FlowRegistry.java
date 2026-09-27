package io.pathfinder.engine.registry;

import io.pathfinder.engine.model.FlowDefinition;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry interface for discovering and retrieving flow definitions by their unique ID.
 */
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
