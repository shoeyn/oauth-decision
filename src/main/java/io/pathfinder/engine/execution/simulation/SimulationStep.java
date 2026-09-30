package io.pathfinder.engine.execution.simulation;

import io.pathfinder.engine.execution.state.BackendStep;
import io.pathfinder.engine.execution.state.FrontendStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimulationStep {
    private final int stepNumber;
    private final String stateId;
    private final String stateType;
    private final List<BackendStep> backendCommands;
    private final List<FrontendStep> frontendScreens;
    private final String transitionTarget;
    private final Map<String, Object> contextSnapshot;

    public SimulationStep(
            int stepNumber,
            String stateId,
            String stateType,
            List<BackendStep> backendCommands,
            List<FrontendStep> frontendScreens,
            String transitionTarget,
            Map<String, Object> contextSnapshot) {
        this.stepNumber = stepNumber;
        this.stateId = stateId;
        this.stateType = stateType;
        this.backendCommands = backendCommands != null ? Collections.unmodifiableList(new ArrayList<>(backendCommands)) : Collections.emptyList();
        this.frontendScreens = frontendScreens != null ? Collections.unmodifiableList(new ArrayList<>(frontendScreens)) : Collections.emptyList();
        this.transitionTarget = transitionTarget;
        this.contextSnapshot = contextSnapshot != null ? Collections.unmodifiableMap(new LinkedHashMap<>(contextSnapshot)) : Collections.emptyMap();
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public String getStateId() {
        return stateId;
    }

    public String getStateType() {
        return stateType;
    }

    public List<BackendStep> getBackendCommands() {
        return backendCommands;
    }

    public List<FrontendStep> getFrontendScreens() {
        return frontendScreens;
    }

    public String getTransitionTarget() {
        return transitionTarget;
    }

    public Map<String, Object> getContextSnapshot() {
        return contextSnapshot;
    }

    public boolean hasBackendCommands() {
        return !backendCommands.isEmpty();
    }

    public boolean hasFrontendScreens() {
        return !frontendScreens.isEmpty();
    }
}
