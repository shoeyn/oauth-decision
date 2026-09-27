package io.pathfinder.engine.runtime;

import java.util.*;

public class SessionContext {
    private final Map<String, Object> data;
    private final Map<String, Object> transientData;
    private final List<String> history;
    private final String currentFlowId;
    private final List<StackFrame> callStack;

    public SessionContext() {
        this(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList());
    }

    public SessionContext(Map<String, Object> data) {
        this(data, Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList());
    }

    public SessionContext(Map<String, Object> data, Map<String, Object> transientData, List<String> history) {
        this(data, transientData, history, null, Collections.emptyList());
    }

    public SessionContext(
            Map<String, Object> data,
            Map<String, Object> transientData,
            List<String> history,
            String currentFlowId,
            List<StackFrame> callStack) {
        this.data = data != null ? Collections.unmodifiableMap(new LinkedHashMap<>(data)) : Collections.emptyMap();
        this.transientData = transientData != null ? Collections.unmodifiableMap(new LinkedHashMap<>(transientData)) : Collections.emptyMap();
        this.history = history != null ? Collections.unmodifiableList(new ArrayList<>(history)) : Collections.emptyList();
        this.currentFlowId = currentFlowId;
        this.callStack = callStack != null ? Collections.unmodifiableList(new ArrayList<>(callStack)) : Collections.emptyList();
    }

    public Map<String, Object> getData() {
        return data;
    }

    public Map<String, Object> getTransientData() {
        return transientData;
    }

    public List<String> getHistory() {
        return history;
    }

    public String getCurrentFlowId() {
        return currentFlowId;
    }

    public List<StackFrame> getCallStack() {
        return callStack;
    }

    public boolean hasCallStack() {
        return !callStack.isEmpty();
    }

    public StackFrame peekFrame() {
        return callStack.isEmpty() ? null : callStack.get(callStack.size() - 1);
    }

    public SessionContext withValue(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        copy.put(key, value);
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack);
    }

    public SessionContext withAll(Map<String, Object> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        copy.putAll(additional);
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack);
    }

    public SessionContext withTransient(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(this.transientData);
        copy.put(key, value);
        return new SessionContext(this.data, copy, this.history, this.currentFlowId, this.callStack);
    }

    public SessionContext withBreadcrumb(String stateId) {
        List<String> copy = new ArrayList<>(this.history);
        copy.add(stateId);
        return new SessionContext(this.data, this.transientData, copy, this.currentFlowId, this.callStack);
    }

    public SessionContext withCurrentFlowId(String flowId) {
        return new SessionContext(this.data, this.transientData, this.history, flowId, this.callStack);
    }

    public SessionContext withPushedFrame(StackFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        List<StackFrame> newStack = new ArrayList<>(this.callStack);
        newStack.add(frame);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, newStack);
    }

    public SessionContext withPoppedFrame() {
        if (this.callStack.isEmpty()) {
            return this;
        }
        List<StackFrame> newStack = new ArrayList<>(this.callStack);
        newStack.remove(newStack.size() - 1);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, newStack);
    }

    public SessionContext clearTransient() {
        return new SessionContext(this.data, Collections.emptyMap(), this.history, this.currentFlowId, this.callStack);
    }
}
