package io.pathfinder.engine.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Represents a single frame on the execution call stack when calling a subflow.
 * Preserves the parent flow and calling state so execution can return to the exact
 * point in the parent flow when the subflow reaches a terminal outcome.
 */
public class StackFrame {
    private final String flowId;
    private final String returnStateId;

    @JsonCreator
    public StackFrame(
            @JsonProperty("flowId") String flowId,
            @JsonProperty("returnStateId") String returnStateId) {
        this.flowId = Objects.requireNonNull(flowId, "flowId must not be null");
        this.returnStateId = Objects.requireNonNull(returnStateId, "returnStateId must not be null");
    }

    public String getFlowId() {
        return flowId;
    }

    public String getReturnStateId() {
        return returnStateId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StackFrame that = (StackFrame) o;
        return Objects.equals(flowId, that.flowId) && Objects.equals(returnStateId, that.returnStateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flowId, returnStateId);
    }

    @Override
    public String toString() {
        return "StackFrame{" +
                "flowId='" + flowId + '\'' +
                ", returnStateId='" + returnStateId + '\'' +
                '}';
    }
}
