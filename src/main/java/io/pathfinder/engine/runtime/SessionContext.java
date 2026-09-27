package io.pathfinder.engine.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

public class SessionContext {
    public static final Set<String> DEFAULT_SENSITIVE_KEYS = Set.of(
            "password", "passwd", "secret", "client_secret",
            "otpCode", "otp", "code", "pin", "ssn", "cvv"
    );

    private final Map<String, Object> data;
    private final Map<String, Object> transientData;
    private final List<String> history;
    private final String currentFlowId;
    private final List<StackFrame> callStack;
    private final Set<String> sensitiveKeys;

    public SessionContext() {
        this(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptySet());
    }

    public SessionContext(Map<String, Object> data) {
        this(data, Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptySet());
    }

    public SessionContext(Map<String, Object> data, Map<String, Object> transientData, List<String> history) {
        this(data, transientData, history, null, Collections.emptyList(), Collections.emptySet());
    }

    public SessionContext(
            Map<String, Object> data,
            Map<String, Object> transientData,
            List<String> history,
            String currentFlowId,
            List<StackFrame> callStack) {
        this(data, transientData, history, currentFlowId, callStack, Collections.emptySet());
    }

    @JsonCreator
    public SessionContext(
            @JsonProperty("data") Map<String, Object> data,
            @JsonProperty("transientData") Map<String, Object> transientData,
            @JsonProperty("history") List<String> history,
            @JsonProperty("currentFlowId") String currentFlowId,
            @JsonProperty("callStack") List<StackFrame> callStack,
            @JsonProperty("sensitiveKeys") Set<String> sensitiveKeys) {
        this.data = data != null ? Collections.unmodifiableMap(new LinkedHashMap<>(data)) : Collections.emptyMap();
        this.transientData = transientData != null ? Collections.unmodifiableMap(new LinkedHashMap<>(transientData)) : Collections.emptyMap();
        this.history = history != null ? Collections.unmodifiableList(new ArrayList<>(history)) : Collections.emptyList();
        this.currentFlowId = currentFlowId;
        this.callStack = callStack != null ? Collections.unmodifiableList(new ArrayList<>(callStack)) : Collections.emptyList();

        Set<String> sensitive = new HashSet<>(sensitiveKeys != null ? sensitiveKeys : Collections.emptySet());
        for (String key : this.data.keySet()) {
            if (isDefaultSensitive(key)) {
                sensitive.add(key);
            }
        }
        this.sensitiveKeys = Collections.unmodifiableSet(sensitive);
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

    public Set<String> getSensitiveKeys() {
        return sensitiveKeys;
    }

    public boolean hasCallStack() {
        return !callStack.isEmpty();
    }

    public StackFrame peekFrame() {
        return callStack.isEmpty() ? null : callStack.get(callStack.size() - 1);
    }

    public boolean isSensitive(String key) {
        return key != null && (sensitiveKeys.contains(key) || isDefaultSensitive(key));
    }

    private static boolean isDefaultSensitive(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        for (String def : DEFAULT_SENSITIVE_KEYS) {
            if (lower.equals(def.toLowerCase()) || lower.endsWith(def.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public SessionContext withSensitiveKey(String key) {
        if (key == null) return this;
        Set<String> copy = new HashSet<>(this.sensitiveKeys);
        copy.add(key);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, this.callStack, copy);
    }

    public SessionContext withSensitiveKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return this;
        Set<String> copy = new HashSet<>(this.sensitiveKeys);
        copy.addAll(keys);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, this.callStack, copy);
    }

    public SessionContext without(String... keys) {
        if (keys == null || keys.length == 0) return this;
        return without(Arrays.asList(keys));
    }

    public SessionContext without(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return this;
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        keys.forEach(copy::remove);
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack, this.sensitiveKeys);
    }

    /**
     * Returns a copy of the session data with all sensitive keys masked with "[REDACTED]".
     * Ideal for safe logging, metrics, and audit trails.
     */
    public Map<String, Object> toSafeMap() {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : this.data.entrySet()) {
            if (isSensitive(entry.getKey())) {
                safe.put(entry.getKey(), "[REDACTED]");
            } else {
                safe.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(safe);
    }

    public SessionContext withValue(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        copy.put(key, value);
        Set<String> sensitive = new HashSet<>(this.sensitiveKeys);
        if (isDefaultSensitive(key)) {
            sensitive.add(key);
        }
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack, sensitive);
    }

    public SessionContext withAll(Map<String, Object> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        copy.putAll(additional);
        Set<String> sensitive = new HashSet<>(this.sensitiveKeys);
        for (String k : additional.keySet()) {
            if (isDefaultSensitive(k)) {
                sensitive.add(k);
            }
        }
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack, sensitive);
    }

    public SessionContext withTransient(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(this.transientData);
        copy.put(key, value);
        return new SessionContext(this.data, copy, this.history, this.currentFlowId, this.callStack, this.sensitiveKeys);
    }

    public SessionContext withBreadcrumb(String stateId) {
        List<String> copy = new ArrayList<>(this.history);
        copy.add(stateId);
        return new SessionContext(this.data, this.transientData, copy, this.currentFlowId, this.callStack, this.sensitiveKeys);
    }

    public SessionContext withCurrentFlowId(String flowId) {
        return new SessionContext(this.data, this.transientData, this.history, flowId, this.callStack, this.sensitiveKeys);
    }

    public SessionContext withPushedFrame(StackFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        List<StackFrame> newStack = new ArrayList<>(this.callStack);
        newStack.add(frame);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, newStack, this.sensitiveKeys);
    }

    public SessionContext withPoppedFrame() {
        if (this.callStack.isEmpty()) {
            return this;
        }
        List<StackFrame> newStack = new ArrayList<>(this.callStack);
        newStack.remove(newStack.size() - 1);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, newStack, this.sensitiveKeys);
    }

    public SessionContext clearTransient() {
        return new SessionContext(this.data, Collections.emptyMap(), this.history, this.currentFlowId, this.callStack, this.sensitiveKeys);
    }

    @Override
    public String toString() {
        return "SessionContext{" +
                "data=" + toSafeMap() +
                ", history=" + history +
                ", currentFlowId='" + currentFlowId + '\'' +
                ", callStack=" + callStack +
                '}';
    }
}
