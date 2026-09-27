package io.pathfinder.engine.oauth2;

import io.pathfinder.engine.runtime.SessionContext;
import io.pathfinder.engine.runtime.TerminalResult;

import java.time.Instant;
import java.util.*;

/**
 * Utility for bridging OAuth 2.1 / OIDC protocol parameters with Pathfinder's {@link SessionContext} and extracting
 * token claims from {@link TerminalResult}.
 */
public final class OAuth2FlowContextHelper {

    private OAuth2FlowContextHelper() {}

    /**
     * Creates an initial {@link SessionContext} populated with standard OAuth 2.1 authorization request details.
     *
     * @param clientId the OAuth 2.1 client identifier
     * @param userId the authenticated user principal identifier (subject)
     * @param requestedScopes the set of scopes requested or pre-determined for this client
     * @param acrValues the requested Authentication Context Class Reference values (or null)
     * @param additionalAttributes additional attributes (e.g. email, roles, IP address, deviceId)
     * @return a populated {@link SessionContext}
     */
    public static SessionContext createInitialContext(
            String clientId,
            String userId,
            Collection<String> requestedScopes,
            String acrValues,
            Map<String, Object> additionalAttributes) {

        Map<String, Object> data = new LinkedHashMap<>();
        if (clientId != null && !clientId.isBlank()) {
            data.put("clientId", clientId);
        }
        if (userId != null && !userId.isBlank()) {
            data.put("userId", userId);
        }
        if (requestedScopes != null && !requestedScopes.isEmpty()) {
            data.put("scopes", new ArrayList<>(requestedScopes));
        }
        if (acrValues != null && !acrValues.isBlank()) {
            data.put("requestedAcr", acrValues);
        }
        data.put("authTime", Instant.now().getEpochSecond());

        if (additionalAttributes != null && !additionalAttributes.isEmpty()) {
            data.putAll(additionalAttributes);
        }

        return new SessionContext(data);
    }

    /**
     * Extracts token claims produced by a successful {@link TerminalResult}.
     *
     * @param terminalResult the terminal outcome of the workflow
     * @return a map of token claims ready for JWT token encoding
     */
    public static Map<String, Object> extractClaims(TerminalResult terminalResult) {
        if (terminalResult == null || terminalResult.getClaims() == null) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(terminalResult.getClaims());
    }

    /**
     * Extracts the Authentication Context Class Reference (acr) claim if present.
     *
     * @param terminalResult the terminal outcome
     * @return an Optional containing the acr value, or empty if not present
     */
    public static Optional<String> extractAcr(TerminalResult terminalResult) {
        if (terminalResult == null || terminalResult.getClaims() == null) {
            return Optional.empty();
        }
        Object acr = terminalResult.getClaims().get(OAuth2ClaimConstants.ACR);
        return acr != null ? Optional.of(acr.toString()) : Optional.empty();
    }

    /**
     * Extracts the Authentication Methods References (amr) claim as a list of strings if present.
     *
     * @param terminalResult the terminal outcome
     * @return a list of authentication method references (e.g. ["pwd", "otp"])
     */
    public static List<String> extractAmr(TerminalResult terminalResult) {
        if (terminalResult == null || terminalResult.getClaims() == null) {
            return Collections.emptyList();
        }
        Object amr = terminalResult.getClaims().get(OAuth2ClaimConstants.AMR);
        if (amr instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return Collections.unmodifiableList(result);
        } else if (amr instanceof String s) {
            return List.of(s);
        }
        return Collections.emptyList();
    }
}
