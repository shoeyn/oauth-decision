package io.pathfinder.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;

public class TerminalConfig {
    private final String status;
    private final Map<String, Object> claims;
    private final String error;

    @JsonCreator
    public TerminalConfig(
            @JsonProperty("status") String status,
            @JsonProperty("claims") Map<String, Object> claims,
            @JsonProperty("error") String error) {
        this.status = status != null ? status : "SUCCESS";
        this.claims = claims != null ? Collections.unmodifiableMap(claims) : Collections.emptyMap();
        this.error = error;
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
}
