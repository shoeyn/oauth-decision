package io.pathfinder.engine.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;

public class TerminalConfig {
    private final String status;
    private final Map<String, Object> claims;
    private final String error;
    private final String errorDescription;
    private final String redirectUrl;
    private final String action;

    public TerminalConfig(String status, Map<String, Object> claims, String error) {
        this(status, claims, error, null, null, null);
    }

    @JsonCreator
    public TerminalConfig(
            @JsonProperty("status") String status,
            @JsonProperty("claims") Map<String, Object> claims,
            @JsonProperty("error") String error,
            @JsonProperty("errorDescription") @JsonAlias({"error_description", "description", "message"}) String errorDescription,
            @JsonProperty("redirectUrl") @JsonAlias({"redirect_url", "failureUrl", "failure_url"}) String redirectUrl,
            @JsonProperty("action") String action) {
        this.status = status != null ? status : "SUCCESS";
        this.claims = claims != null ? Collections.unmodifiableMap(claims) : Collections.emptyMap();
        this.error = error;
        this.errorDescription = errorDescription;
        this.redirectUrl = redirectUrl;
        this.action = action;
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

    public String getErrorDescription() {
        return errorDescription;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public String getAction() {
        return action;
    }
}
