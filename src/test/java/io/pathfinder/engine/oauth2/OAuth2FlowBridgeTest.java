package io.pathfinder.engine.oauth2;

import io.pathfinder.engine.runtime.SessionContext;
import io.pathfinder.engine.runtime.TerminalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2FlowBridgeTest {

    @Test
    void testCreateInitialContextPopulatesOAuthParameters() {
        SessionContext context = OAuth2FlowContextHelper.createInitialContext(
                "demo_client",
                "user_456",
                List.of("openid", "profile", "email"),
                "urn:pathfinder:auth:level2",
                Map.of("ip_address", "192.168.1.1", "device_id", "dev_xyz")
        );

        assertThat(context.getData().get("clientId")).isEqualTo("demo_client");
        assertThat(context.getData().get("userId")).isEqualTo("user_456");
        assertThat(context.getData().get("scopes")).isEqualTo(List.of("openid", "profile", "email"));
        assertThat(context.getData().get("requestedAcr")).isEqualTo("urn:pathfinder:auth:level2");
        assertThat(context.getData().get("ip_address")).isEqualTo("192.168.1.1");
        assertThat(context.getData().get("device_id")).isEqualTo("dev_xyz");
        assertThat(context.getData()).containsKey("authTime");
    }

    @Test
    void testExtractClaimsFromTerminalResult() {
        TerminalResult terminalResult = new TerminalResult(
                "SUCCESS",
                Map.of(
                        OAuth2ClaimConstants.SUB, "user_456",
                        OAuth2ClaimConstants.ACR, "urn:pathfinder:auth:level2",
                        OAuth2ClaimConstants.AMR, List.of("pwd", "otp"),
                        "custom_claim", "custom_val"
                ),
                null
        );

        Map<String, Object> claims = OAuth2FlowContextHelper.extractClaims(terminalResult);
        assertThat(claims).containsEntry("custom_claim", "custom_val");

        Optional<String> acr = OAuth2FlowContextHelper.extractAcr(terminalResult);
        assertThat(acr).contains("urn:pathfinder:auth:level2");

        List<String> amr = OAuth2FlowContextHelper.extractAmr(terminalResult);
        assertThat(amr).containsExactly("pwd", "otp");
    }

    @Test
    void testExtractClaimsWithNullOrSingleStringAmr() {
        TerminalResult stringAmrResult = new TerminalResult(
                "SUCCESS",
                Map.of(OAuth2ClaimConstants.AMR, "pwd"),
                null
        );

        List<String> amr = OAuth2FlowContextHelper.extractAmr(stringAmrResult);
        assertThat(amr).containsExactly("pwd");

        assertThat(OAuth2FlowContextHelper.extractClaims(null)).isEmpty();
        assertThat(OAuth2FlowContextHelper.extractAcr(null)).isEmpty();
        assertThat(OAuth2FlowContextHelper.extractAmr(null)).isEmpty();
    }
}
