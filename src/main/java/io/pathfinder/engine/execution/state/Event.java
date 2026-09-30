package io.pathfinder.engine.execution.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class Event {
    public static final String TYPE_START = "START";
    public static final String TYPE_SUBMIT = "SUBMIT";
    public static final String TYPE_RESUME = "RESUME";

    private final String type;
    private final Map<String, Object> payload;

    public Event(String type, Map<String, Object> payload) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.payload = payload != null ? Collections.unmodifiableMap(new LinkedHashMap<>(payload)) : Collections.emptyMap();
    }

    public static Event start() {
        return new Event(TYPE_START, Collections.emptyMap());
    }

    public static Event submit(Map<String, Object> input) {
        return new Event(TYPE_SUBMIT, input);
    }

    public static Event resume(Map<String, Object> batchResults) {
        return new Event(TYPE_RESUME, batchResults);
    }

    public static Event of(String type, Map<String, Object> payload) {
        return new Event(type, payload);
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
