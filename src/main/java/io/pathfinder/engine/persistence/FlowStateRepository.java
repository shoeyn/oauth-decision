package io.pathfinder.engine.persistence;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage SPI for persisting and retrieving in-flight workflow instances across distributed nodes.
 */
public interface FlowStateRepository {

    /**
     * Persists the given flow state associated with the flow instance identifier, expiring after the given TTL.
     *
     * @param flowInstanceId the unique execution transaction or instance identifier
     * @param state the flow state snapshot
     * @param ttl time-to-live duration before expiration
     */
    void save(String flowInstanceId, FlowState state, Duration ttl);

    /**
     * Retrieves an active flow state by its instance identifier.
     *
     * @param flowInstanceId the unique execution identifier
     * @return the flow state if found and not expired, or empty otherwise
     */
    Optional<FlowState> find(String flowInstanceId);

    /**
     * Deletes the flow state once completed, rejected, or revoked.
     *
     * @param flowInstanceId the unique execution identifier
     */
    void delete(String flowInstanceId);
}
