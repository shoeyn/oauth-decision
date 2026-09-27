package io.pathfinder.engine.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory implementation of {@link FlowStateRepository} with TTL expiration support.
 */
public class InMemoryFlowStateRepository implements FlowStateRepository {

    private record Entry(FlowState state, Instant expiresAt) {
        boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void save(String flowInstanceId, FlowState state, Duration ttl) {
        Objects.requireNonNull(flowInstanceId, "flowInstanceId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Instant expiresAt = ttl != null ? Instant.now().plus(ttl) : null;
        store.put(flowInstanceId, new Entry(state, expiresAt));
    }

    @Override
    public Optional<FlowState> find(String flowInstanceId) {
        if (flowInstanceId == null) {
            return Optional.empty();
        }
        Entry entry = store.get(flowInstanceId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            store.remove(flowInstanceId);
            return Optional.empty();
        }
        return Optional.of(entry.state());
    }

    @Override
    public void delete(String flowInstanceId) {
        if (flowInstanceId != null) {
            store.remove(flowInstanceId);
        }
    }

    public void clear() {
        store.clear();
    }
}
