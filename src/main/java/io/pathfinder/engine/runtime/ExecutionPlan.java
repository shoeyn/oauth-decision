package io.pathfinder.engine.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecutionPlan {
    private final String currentState;
    private final List<BackendStep> backendSteps;
    private final List<FrontendStep> frontendSteps;
    private final Checkpoint checkpoint;
    private final TerminalResult terminal;
    private final SessionContext updatedContext;

    @JsonCreator
    public ExecutionPlan(
            @JsonProperty("currentState") String currentState,
            @JsonProperty("backendSteps") List<BackendStep> backendSteps,
            @JsonProperty("frontendSteps") List<FrontendStep> frontendSteps,
            @JsonProperty("checkpoint") Checkpoint checkpoint,
            @JsonProperty("terminal") TerminalResult terminal,
            @JsonProperty("updatedContext") SessionContext updatedContext) {
        this.currentState = currentState;
        this.backendSteps = backendSteps != null ? Collections.unmodifiableList(new ArrayList<>(backendSteps)) : Collections.emptyList();
        this.frontendSteps = frontendSteps != null ? Collections.unmodifiableList(new ArrayList<>(frontendSteps)) : Collections.emptyList();
        this.checkpoint = checkpoint;
        this.terminal = terminal;
        this.updatedContext = updatedContext;
    }

    public String getCurrentState() {
        return currentState;
    }

    public List<BackendStep> getBackendSteps() {
        return backendSteps;
    }

    public List<FrontendStep> getFrontendSteps() {
        return frontendSteps;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public TerminalResult getTerminal() {
        return terminal;
    }

    public TerminalResult getTerminalResult() {
        return terminal;
    }

    public SessionContext getUpdatedContext() {
        return updatedContext;
    }

    public boolean isTerminal() {
        return terminal != null;
    }

    public boolean hasCheckpoint() {
        return checkpoint != null;
    }

    public boolean hasBackendSteps() {
        return !backendSteps.isEmpty();
    }

    public boolean hasFrontendSteps() {
        return !frontendSteps.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String currentState;
        private final List<BackendStep> backendSteps = new ArrayList<>();
        private final List<FrontendStep> frontendSteps = new ArrayList<>();
        private Checkpoint checkpoint;
        private TerminalResult terminal;
        private SessionContext updatedContext;

        public Builder currentState(String currentState) {
            this.currentState = currentState;
            return this;
        }

        public Builder addBackendStep(BackendStep step) {
            if (step != null) {
                this.backendSteps.add(step);
            }
            return this;
        }

        public Builder addBackendSteps(List<BackendStep> steps) {
            if (steps != null) {
                this.backendSteps.addAll(steps);
            }
            return this;
        }

        public Builder addFrontendStep(FrontendStep step) {
            if (step != null) {
                this.frontendSteps.add(step);
            }
            return this;
        }

        public Builder addFrontendSteps(List<FrontendStep> steps) {
            if (steps != null) {
                this.frontendSteps.addAll(steps);
            }
            return this;
        }

        public Builder checkpoint(Checkpoint checkpoint) {
            this.checkpoint = checkpoint;
            return this;
        }

        public Builder terminal(TerminalResult terminal) {
            this.terminal = terminal;
            return this;
        }

        public Builder updatedContext(SessionContext context) {
            this.updatedContext = context;
            return this;
        }

        public ExecutionPlan build() {
            return new ExecutionPlan(
                    currentState,
                    backendSteps,
                    frontendSteps,
                    checkpoint,
                    terminal,
                    updatedContext
            );
        }
    }
}
