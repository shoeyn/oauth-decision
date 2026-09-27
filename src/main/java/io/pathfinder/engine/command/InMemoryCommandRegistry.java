package io.pathfinder.engine.command;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory implementation of {@link CommandRegistry}.
 */
public class InMemoryCommandRegistry implements CommandRegistry {
    private final Map<String, CommandHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(String serviceId, CommandHandler handler) {
        Objects.requireNonNull(serviceId, "serviceId must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        if (serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        handlers.put(serviceId, handler);
    }

    @Override
    public Optional<CommandHandler> getHandler(String serviceId) {
        if (serviceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(serviceId));
    }

    @Override
    public boolean hasHandler(String serviceId) {
        if (serviceId == null) {
            return false;
        }
        return handlers.containsKey(serviceId);
    }
}
