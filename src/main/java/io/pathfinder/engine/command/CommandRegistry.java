package io.pathfinder.engine.command;

import java.util.Optional;

/**
 * Registry for mapping backend service identifiers to their executable {@link CommandHandler} implementations.
 */
public interface CommandRegistry {

    /**
     * Registers a command handler for a given service identifier.
     *
     * @param serviceId the service name (e.g. "fraud-engine", "otp-service")
     * @param handler the handler implementation
     */
    void register(String serviceId, CommandHandler handler);

    /**
     * Retrieves the command handler registered for the specified service identifier.
     *
     * @param serviceId the service name
     * @return the registered handler, or empty if none exists
     */
    Optional<CommandHandler> getHandler(String serviceId);

    /**
     * Checks if a command handler is registered for the specified service identifier.
     *
     * @param serviceId the service name
     * @return true if a handler is registered, false otherwise
     */
    boolean hasHandler(String serviceId);
}
