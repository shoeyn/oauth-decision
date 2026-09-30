package io.pathfinder.engine.execution.state;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TerminalResult {
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String ACTION_REDIRECT = "REDIRECT";
    public static final String ACTION_UI = "UI";
    public static final String ACTION_UI_DROPOUT = "UI_DROPOUT";
    public static final String ACTION_COMPLETE = "COMPLETE";

    private final String status;
    private final Map<String, Object> claims;
    private final String error;
    private final String errorDescription;
    private final String redirectUrl;
    private final String action;

    public TerminalResult(String status, Map<String, Object> claims, String error) {
        this(status, claims, error, null, null, null);
    }

    @JsonCreator
    public TerminalResult(
            @JsonProperty("status") String status,
            @JsonProperty("claims") Map<String, Object> claims,
            @JsonProperty("error") String error,
            @JsonProperty("errorDescription") @JsonAlias({"error_description", "description", "message"}) String errorDescription,
            @JsonProperty("redirectUrl") @JsonAlias({"redirect_url", "failureUrl", "failure_url"}) String redirectUrl,
            @JsonProperty("action") String action) {
        this.status = status != null ? status : STATUS_SUCCESS;
        this.claims = claims != null ? Collections.unmodifiableMap(claims) : Collections.emptyMap();
        this.error = error;
        this.errorDescription = errorDescription;
        this.redirectUrl = redirectUrl;
        this.action = action != null ? action : (redirectUrl != null && !redirectUrl.isBlank() ? ACTION_REDIRECT : ACTION_COMPLETE);
    }

    public static TerminalResult success(Map<String, Object> claims) {
        return new TerminalResult(STATUS_SUCCESS, claims, null, null, null, ACTION_COMPLETE);
    }

    public static TerminalResult denied(String error) {
        return new TerminalResult(STATUS_DENIED, Collections.emptyMap(), error, null, null, ACTION_COMPLETE);
    }

    public static TerminalResult denied(String error, String errorDescription) {
        return new TerminalResult(STATUS_DENIED, Collections.emptyMap(), error, errorDescription, null, ACTION_COMPLETE);
    }

    public static TerminalResult redirect(String status, String redirectUrl, String error, String errorDescription) {
        return new TerminalResult(status, Collections.emptyMap(), error, errorDescription, redirectUrl, ACTION_REDIRECT);
    }

    public static TerminalResult uiDropout(String error, String errorDescription) {
        return new TerminalResult(STATUS_DENIED, Collections.emptyMap(), error, errorDescription, null, ACTION_UI);
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

    public boolean isSuccess() {
        return STATUS_SUCCESS.equalsIgnoreCase(status);
    }

    public boolean isDenied() {
        return !isSuccess();
    }

    public boolean isRedirect() {
        return ACTION_REDIRECT.equalsIgnoreCase(action) || (redirectUrl != null && !redirectUrl.isBlank());
    }

    public boolean isUiDropout() {
        return ACTION_UI.equalsIgnoreCase(action) || ACTION_UI_DROPOUT.equalsIgnoreCase(action);
    }

    @Override
    public String toString() {
        return "TerminalResult{" +
                "status='" + status + '\'' +
                ", claims=" + claims +
                ", error='" + error + '\'' +
                ", errorDescription='" + errorDescription + '\'' +
                ", redirectUrl='" + redirectUrl + '\'' +
                ", action='" + action + '\'' +
                '}';
    }
}
