package io.pathfinder.engine.registry;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.parser.FlowParser;

import java.io.File;
import java.util.Collection;
import java.util.Optional;

/**
 * FileSystem-backed {@link FlowRegistry} that can load flows from files or directories.
 */
public class FileSystemFlowRegistry implements FlowRegistry {
    private final InMemoryFlowRegistry delegate = new InMemoryFlowRegistry();
    private final FlowParser parser;

    public FileSystemFlowRegistry() {
        this(new FlowParser());
    }

    public FileSystemFlowRegistry(FlowParser parser) {
        this.parser = parser != null ? parser : new FlowParser();
    }

    /**
     * Loads and registers a flow from a specific file.
     */
    public FlowDefinition registerFile(File file) {
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a file: " + file.getAbsolutePath());
        }
        FlowDefinition flow = parser.parseYaml(file);
        register(flow);
        return flow;
    }

    /**
     * Scans a directory for .yaml, .yml, or .json flow definitions and registers them.
     */
    public void registerDirectory(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("Path is not a valid directory: " + directory.getAbsolutePath());
        }
        File[] files = directory.listFiles((dir, name) ->
                name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                registerFile(file);
            }
        }
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
