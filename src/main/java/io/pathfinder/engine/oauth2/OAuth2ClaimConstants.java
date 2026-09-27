package io.pathfinder.engine.oauth2;

/**
 * Standard claim names and protocol parameters for OAuth 2.1 and OpenID Connect (OIDC) integrations.
 */
public final class OAuth2ClaimConstants {
    private OAuth2ClaimConstants() {}

    public static final String SUB = "sub";
    public static final String ACR = "acr";
    public static final String AMR = "amr";
    public static final String AUTH_TIME = "auth_time";
    public static final String CLIENT_ID = "client_id";
    public static final String SCOPE = "scope";
    public static final String USER_ID = "user_id";
    public static final String EMAIL = "email";
    public static final String ROLES = "roles";
    public static final String RISK_SCORE = "risk_score";
    public static final String DEVICE_ID = "device_id";
    public static final String IP_ADDRESS = "ip_address";
}
