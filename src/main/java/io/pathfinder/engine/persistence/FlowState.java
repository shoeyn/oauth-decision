package io.pathfinder.engine.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.pathfinder.engine.runtime.Checkpoint;
import io.pathfinder.engine.runtime.SessionContext;

import java.time.Instant;
import java.util.Objects;

/**
 * Serializable snapshot of a workflow instance in flight, suitable for storage in Redis or a relational database.
 */
public class FlowState {
    private final String flowInstanceId;
    private final String flowId;
    private final String currentStateId;
    private final SessionContext context;
    private final Checkpoint checkpoint;
    private final Instant createdAt;
    private final Instant updatedAt;

    public FlowState(String flowInstanceId, String flowId, String currentStateId, SessionContext context, Checkpoint checkpoint) {
        this(flowInstanceId, flowId, currentStateId, context, checkpoint, Instant.now(), Instant.now());
    }

    @JsonCreator
    public FlowState(
            @JsonProperty("flowInstanceId") String flowInstanceId,
            @JsonProperty("flowId") String flowId,
            @JsonProperty("currentStateId") String currentStateId,
            @JsonProperty("context") SessionContext context,
            @JsonProperty("checkpoint") Checkpoint checkpoint,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt) {
        this.flowInstanceId = Objects.requireNonNull(flowInstanceId, "flowInstanceId must not be null");
        this.flowId = flowId;
        this.currentStateId = currentStateId;
        this.context = context != null ? context : new SessionContext();
        this.checkpoint = checkpoint;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public String getFlowInstanceId() {
        return flowInstanceId;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getCurrentStateId() {
        return currentStateId;
    }

    public SessionContext getContext() {
        return context;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public FlowState withUpdatedState(String newStateId, SessionContext newContext, Checkpoint newCheckpoint) {
        return new FlowState(this.flowInstanceId, this.flowId, newStateId, newContext, newCheckpoint, this.createdAt, Instant.now());
    }
}
