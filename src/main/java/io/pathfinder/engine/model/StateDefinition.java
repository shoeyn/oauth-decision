package io.pathfinder.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StateDefinition {
    private final String id;
    private final StateType type;
    private final String subflow;
    private final List<CommandDefinition> backendCommands;
    private final List<FrontendSchemaDefinition> frontendSchemas;
    private final TerminalConfig terminalConfig;
    private final List<TransitionDefinition> transitions;
    private final boolean forceCheckpoint;

    public StateDefinition(
            String id,
            StateType type,
            CommandDefinition singleCommand,
            List<CommandDefinition> commandList,
            FrontendSchemaDefinition singleSchema,
            List<FrontendSchemaDefinition> schemaList,
            TerminalConfig terminalConfig,
            List<TransitionDefinition> transitions,
            Boolean forceCheckpoint) {
        this(id, type, singleCommand, commandList, singleSchema, schemaList, terminalConfig, transitions, forceCheckpoint, null);
    }

    @JsonCreator
    public StateDefinition(
            @JsonProperty("id") String id,
            @JsonProperty("type") StateType type,
            @JsonProperty("command") CommandDefinition singleCommand,
            @JsonProperty("commands") List<CommandDefinition> commandList,
            @JsonProperty("schema") FrontendSchemaDefinition singleSchema,
            @JsonProperty("schemas") List<FrontendSchemaDefinition> schemaList,
            @JsonProperty("terminal") TerminalConfig terminalConfig,
            @JsonProperty("on") List<TransitionDefinition> transitions,
            @JsonProperty("checkpoint") Boolean forceCheckpoint,
            @JsonProperty("subflow") String subflow) {
        this.id = id;
        this.subflow = subflow;
        if (type != null) {
            this.type = type;
        } else if (subflow != null && !subflow.isBlank()) {
            this.type = StateType.SUBFLOW;
        } else {
            this.type = StateType.DECISION_FORK;
        }

        List<CommandDefinition> cmds = new ArrayList<>();
        if (singleCommand != null) {
            cmds.add(singleCommand);
        }
        if (commandList != null) {
            cmds.addAll(commandList);
        }
        this.backendCommands = Collections.unmodifiableList(cmds);

        List<FrontendSchemaDefinition> schs = new ArrayList<>();
        if (singleSchema != null) {
            schs.add(singleSchema);
        }
        if (schemaList != null) {
            schs.addAll(schemaList);
        }
        this.frontendSchemas = Collections.unmodifiableList(schs);

        this.terminalConfig = terminalConfig;
        this.transitions = transitions != null ? Collections.unmodifiableList(transitions) : Collections.emptyList();
        this.forceCheckpoint = Boolean.TRUE.equals(forceCheckpoint);
    }

    public String getId() {
        return id;
    }

    public StateType getType() {
        return type;
    }

    public String getSubflow() {
        return subflow;
    }

    public List<CommandDefinition> getBackendCommands() {
        return backendCommands;
    }

    public List<FrontendSchemaDefinition> getFrontendSchemas() {
        return frontendSchemas;
    }

    public TerminalConfig getTerminalConfig() {
        return terminalConfig;
    }

    public List<TransitionDefinition> getTransitions() {
        return transitions;
    }

    public boolean isForceCheckpoint() {
        return forceCheckpoint;
    }
}
