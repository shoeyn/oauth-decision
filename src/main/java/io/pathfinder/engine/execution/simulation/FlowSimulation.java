package io.pathfinder.engine.execution.simulation;

import io.pathfinder.engine.execution.state.BackendStep;
import io.pathfinder.engine.execution.state.Checkpoint;
import io.pathfinder.engine.execution.state.FrontendStep;
import io.pathfinder.engine.execution.state.SessionContext;
import io.pathfinder.engine.execution.state.TerminalResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FlowSimulation {
    private final String flowId;
    private final List<SimulationStep> steps;
    private final SessionContext finalContext;
    private final TerminalResult terminalResult;
    private final Checkpoint pausedCheckpoint;
    private final boolean completed;

    public FlowSimulation(
            String flowId,
            List<SimulationStep> steps,
            SessionContext finalContext,
            TerminalResult terminalResult,
            Checkpoint pausedCheckpoint,
            boolean completed) {
        this.flowId = flowId;
        this.steps = steps != null ? Collections.unmodifiableList(new ArrayList<>(steps)) : Collections.emptyList();
        this.finalContext = finalContext;
        this.terminalResult = terminalResult;
        this.pausedCheckpoint = pausedCheckpoint;
        this.completed = completed;
    }

    public String getFlowId() {
        return flowId;
    }

    public List<SimulationStep> getSteps() {
        return steps;
    }

    public SessionContext getFinalContext() {
        return finalContext;
    }

    public TerminalResult getTerminalResult() {
        return terminalResult;
    }

    public Checkpoint getPausedCheckpoint() {
        return pausedCheckpoint;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isTerminal() {
        return terminalResult != null;
    }

    public boolean isSuccess() {
        return terminalResult != null && "SUCCESS".equalsIgnoreCase(terminalResult.getStatus());
    }

    public boolean isDenied() {
        return terminalResult != null && !"SUCCESS".equalsIgnoreCase(terminalResult.getStatus());
    }

    /**
     * Returns the ordered list of state IDs traversed during simulation.
     */
    public List<String> getExecutionPath() {
        List<String> path = new ArrayList<>();
        for (SimulationStep step : steps) {
            path.add(step.getStateId());
        }
        return Collections.unmodifiableList(path);
    }

    /**
     * Returns all backend commands emitted across the simulated execution trajectory.
     */
    public List<BackendStep> getAllCommands() {
        List<BackendStep> all = new ArrayList<>();
        for (SimulationStep step : steps) {
            all.addAll(step.getBackendCommands());
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Returns all frontend screens/forms displayed across the simulated execution trajectory.
     */
    public List<FrontendStep> getAllScreens() {
        List<FrontendStep> all = new ArrayList<>();
        for (SimulationStep step : steps) {
            all.addAll(step.getFrontendScreens());
        }
        return Collections.unmodifiableList(all);
    }

    public boolean containsState(String stateId) {
        return getExecutionPath().contains(stateId);
    }

    /**
     * Produces a human-readable visual ASCII trace of the simulated workflow.
     */
    public String toVisualTrace() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Flow Simulation Trace: ").append(flowId).append(" ===\n");
        for (SimulationStep step : steps) {
            sb.append("[").append(step.getStepNumber()).append("] State: ").append(step.getStateId())
                    .append(" (").append(step.getStateType()).append(")\n");

            if (step.hasBackendCommands()) {
                sb.append("    Commands (").append(step.getBackendCommands().size()).append("):\n");
                for (BackendStep cmd : step.getBackendCommands()) {
                    sb.append("      • ").append(cmd.getStepId()).append(" (service: ").append(cmd.getService()).append(")\n");
                }
            }

            if (step.hasFrontendScreens()) {
                sb.append("    Screens (").append(step.getFrontendScreens().size()).append("):\n");
                for (FrontendStep screen : step.getFrontendScreens()) {
                    sb.append("      • ").append(screen.getScreenId());
                    if (screen.getTitle() != null && !screen.getTitle().isBlank()) {
                        sb.append(" (\"").append(screen.getTitle()).append("\")");
                    }
                    sb.append("\n");
                }
            }

            if (step.getTransitionTarget() != null) {
                sb.append("    Transition ➔ ").append(step.getTransitionTarget()).append("\n");
            }
        }

        if (terminalResult != null) {
            sb.append("[Outcome] ").append(terminalResult.getStatus());
            if (terminalResult.getError() != null) {
                sb.append(" (Error: ").append(terminalResult.getError());
                if (terminalResult.getErrorDescription() != null && !terminalResult.getErrorDescription().isBlank()) {
                    sb.append(": ").append(terminalResult.getErrorDescription());
                }
                sb.append(")");
            }
            if (terminalResult.isRedirect()) {
                sb.append(" [Redirect ➔ ").append(terminalResult.getRedirectUrl()).append("]");
            } else if (terminalResult.isUiDropout()) {
                sb.append(" [UI Dropout: Stays in UI]");
            }
            if (!terminalResult.getClaims().isEmpty()) {
                sb.append(" Claims: ").append(terminalResult.getClaims());
            }
            sb.append("\n");
        } else if (pausedCheckpoint != null) {
            sb.append("[Paused] Checkpoint at '").append(pausedCheckpoint.getResumeState())
                    .append("', waiting for inputs: ").append(pausedCheckpoint.getExpectedInputs()).append("\n");
        }

        return sb.toString();
    }
}
