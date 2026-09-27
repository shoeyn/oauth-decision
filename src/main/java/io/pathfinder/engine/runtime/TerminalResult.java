package io.pathfinder.engine.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;

public class TerminalResult {
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_ERROR = "ERROR";

    private final String status;
    private final Map<String, Object> claims;
    private final String error;

    @JsonCreator
    public TerminalResult(
            @JsonProperty("status") String status,
            @JsonProperty("claims") Map<String, Object> claims,
            @JsonProperty("error") String error) {
        this.status = status != null ? status : STATUS_SUCCESS;
        this.claims = claims != null ? Collections.unmodifiableMap(claims) : Collections.emptyMap();
        this.error = error;
    }

    public static TerminalResult success(Map<String, Object> claims) {
        return new TerminalResult(STATUS_SUCCESS, claims, null);
    }

    public static TerminalResult denied(String error) {
        return new TerminalResult(STATUS_DENIED, Collections.emptyMap(), error);
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getClaims() {
        return claims;
    }

    public String getError() {
        return error;
    }

    public boolean isSuccess() {
        return STATUS_SUCCESS.equalsIgnoreCase(status);
    }

    @Override
    public String toString() {
        return "TerminalResult{" +
                "status='" + status + '\'' +
                ", claims=" + claims +
                ", error='" + error + '\'' +
                '}';
    }
}
