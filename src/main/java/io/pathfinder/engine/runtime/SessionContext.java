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
    private final Map<String, Object> input;
    private final Map<String, Integer> attempts;

    public SessionContext() {
        this(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
    }

    public SessionContext(Map<String, Object> data) {
        this(data, Collections.emptyMap(), Collections.emptyList(), null, Collections.emptyList(), Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
    }

    public SessionContext(Map<String, Object> data, Map<String, Object> transientData, List<String> history) {
        this(data, transientData, history, null, Collections.emptyList(), Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
    }

    public SessionContext(
            Map<String, Object> data,
            Map<String, Object> transientData,
            List<String> history,
            String currentFlowId,
            List<StackFrame> callStack) {
        this(data, transientData, history, currentFlowId, callStack, Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
    }

    public SessionContext(
            Map<String, Object> data,
            Map<String, Object> transientData,
            List<String> history,
            String currentFlowId,
            List<StackFrame> callStack,
            Set<String> sensitiveKeys) {
        this(data, transientData, history, currentFlowId, callStack, sensitiveKeys, Collections.emptyMap(), Collections.emptyMap());
    }

    @JsonCreator
    public SessionContext(
            @JsonProperty("data") Map<String, Object> data,
            @JsonProperty("transientData") Map<String, Object> transientData,
            @JsonProperty("history") List<String> history,
            @JsonProperty("currentFlowId") String currentFlowId,
            @JsonProperty("callStack") List<StackFrame> callStack,
            @JsonProperty("sensitiveKeys") Set<String> sensitiveKeys,
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("attempts") Map<String, Integer> attempts) {
        this.data = data != null ? Collections.unmodifiableMap(new LinkedHashMap<>(data)) : Collections.emptyMap();
        this.transientData = transientData != null ? Collections.unmodifiableMap(new LinkedHashMap<>(transientData)) : Collections.emptyMap();
        this.history = history != null ? Collections.unmodifiableList(new ArrayList<>(history)) : Collections.emptyList();
        this.currentFlowId = currentFlowId;
        this.callStack = callStack != null ? Collections.unmodifiableList(new ArrayList<>(callStack)) : Collections.emptyList();
        this.input = input != null ? Collections.unmodifiableMap(new LinkedHashMap<>(input)) : Collections.emptyMap();
        this.attempts = attempts != null ? Collections.unmodifiableMap(new LinkedHashMap<>(attempts)) : Collections.emptyMap();

        Set<String> sensitive = new HashSet<>(sensitiveKeys != null ? sensitiveKeys : Collections.emptySet());
        for (String key : this.data.keySet()) {
            if (isDefaultSensitive(key)) {
                sensitive.add(key);
            }
        }
        for (String key : this.input.keySet()) {
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

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Integer> getAttempts() {
        return attempts;
    }

    public int getAttemptCount(String stateId) {
        return attempts.getOrDefault(stateId, 0);
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
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, this.callStack, copy, this.input, this.attempts);
    }

    public SessionContext withSensitiveKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return this;
        Set<String> copy = new HashSet<>(this.sensitiveKeys);
        copy.addAll(keys);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, this.callStack, copy, this.input, this.attempts);
    }

    public SessionContext without(String... keys) {
        if (keys == null || keys.length == 0) return this;
        return without(Arrays.asList(keys));
    }

    public SessionContext without(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return this;
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        keys.forEach(copy::remove);
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack, this.sensitiveKeys, this.input, this.attempts);
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
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack, sensitive, this.input, this.attempts);
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
        return new SessionContext(copy, this.transientData, this.history, this.currentFlowId, this.callStack, sensitive, this.input, this.attempts);
    }

    public SessionContext withInput(Map<String, Object> inputPayload) {
        Map<String, Object> newInput = inputPayload != null ? new LinkedHashMap<>(inputPayload) : Collections.emptyMap();
        Set<String> sensitive = new HashSet<>(this.sensitiveKeys);
        for (String k : newInput.keySet()) {
            if (isDefaultSensitive(k)) {
                sensitive.add(k);
            }
        }
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, this.callStack, sensitive, newInput, this.attempts);
    }

    public SessionContext withIncrementedAttempt(String stateId) {
        if (stateId == null || stateId.isBlank()) {
            return this;
        }
        Map<String, Integer> newAttempts = new LinkedHashMap<>(this.attempts);
        newAttempts.put(stateId, newAttempts.getOrDefault(stateId, 0) + 1);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, this.callStack, this.sensitiveKeys, this.input, newAttempts);
    }

    public SessionContext withResetAttempts(String stateId) {
        if (stateId == null || !this.attempts.containsKey(stateId)) {
            return this;
        }
        Map<String, Integer> newAttempts = new LinkedHashMap<>(this.attempts);
        newAttempts.remove(stateId);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, this.callStack, this.sensitiveKeys, this.input, newAttempts);
    }

    public SessionContext withTransient(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(this.transientData);
        copy.put(key, value);
        return new SessionContext(this.data, copy, this.history, this.currentFlowId, this.callStack, this.sensitiveKeys, this.input, this.attempts);
    }

    public SessionContext withBreadcrumb(String stateId) {
        List<String> copy = new ArrayList<>(this.history);
        copy.add(stateId);
        return new SessionContext(this.data, this.transientData, copy, this.currentFlowId, this.callStack, this.sensitiveKeys, this.input, this.attempts);
    }

    public SessionContext withCurrentFlowId(String flowId) {
        return new SessionContext(this.data, this.transientData, this.history, flowId, this.callStack, this.sensitiveKeys, this.input, this.attempts);
    }

    public SessionContext withPushedFrame(StackFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        List<StackFrame> newStack = new ArrayList<>(this.callStack);
        newStack.add(frame);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, newStack, this.sensitiveKeys, this.input, this.attempts);
    }

    public SessionContext withPoppedFrame() {
        if (this.callStack.isEmpty()) {
            return this;
        }
        List<StackFrame> newStack = new ArrayList<>(this.callStack);
        newStack.remove(newStack.size() - 1);
        return new SessionContext(this.data, this.transientData, this.history, this.currentFlowId, newStack, this.sensitiveKeys, this.input, this.attempts);
    }

    public SessionContext clearTransient() {
        return new SessionContext(this.data, Collections.emptyMap(), this.history, this.currentFlowId, this.callStack, this.sensitiveKeys, this.input, this.attempts);
    }

    @Override
    public String toString() {
        return "SessionContext{" +
                "data=" + toSafeMap() +
                ", input=" + input +
                ", attempts=" + attempts +
                ", history=" + history +
                ", currentFlowId='" + currentFlowId + '\'' +
                ", callStack=" + callStack +
                '}';
    }
}
