package io.pathfinder.engine.runtime;

import java.util.*;

public class SessionContext {
    private final Map<String, Object> data;
    private final Map<String, Object> transientData;
    private final List<String> history;

    public SessionContext() {
        this(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
    }

    public SessionContext(Map<String, Object> data) {
        this(data, Collections.emptyMap(), Collections.emptyList());
    }

    public SessionContext(Map<String, Object> data, Map<String, Object> transientData, List<String> history) {
        this.data = data != null ? Collections.unmodifiableMap(new LinkedHashMap<>(data)) : Collections.emptyMap();
        this.transientData = transientData != null ? Collections.unmodifiableMap(new LinkedHashMap<>(transientData)) : Collections.emptyMap();
        this.history = history != null ? Collections.unmodifiableList(new ArrayList<>(history)) : Collections.emptyList();
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

    public SessionContext withValue(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        copy.put(key, value);
        return new SessionContext(copy, this.transientData, this.history);
    }

    public SessionContext withAll(Map<String, Object> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }
        Map<String, Object> copy = new LinkedHashMap<>(this.data);
        copy.putAll(additional);
        return new SessionContext(copy, this.transientData, this.history);
    }

    public SessionContext withTransient(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(this.transientData);
        copy.put(key, value);
        return new SessionContext(this.data, copy, this.history);
    }

    public SessionContext withBreadcrumb(String stateId) {
        List<String> copy = new ArrayList<>(this.history);
        copy.add(stateId);
        return new SessionContext(this.data, this.transientData, copy);
    }

    public SessionContext clearTransient() {
        return new SessionContext(this.data, Collections.emptyMap(), this.history);
    }
}
