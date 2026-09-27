package io.pathfinder.engine.command;

import java.util.Map;

/**
 * Functional interface for executing backend service commands declared in state definitions.
 */
@FunctionalInterface
public interface CommandHandler {

    /**
     * Executes the backend command with the provided resolved payload.
     *
     * @param payload the resolved parameter payload for this command
     * @return a map of result values to be bound into the flow's evaluation bindings
     * @throws Exception if backend execution encounters an error
     */
    Map<String, Object> execute(Map<String, Object> payload) throws Exception;
}
