package io.pathfinder.engine.core;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.parser.FlowParser;
import io.pathfinder.engine.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class FlowSimulationTest {
    private FlowDefinition stepUpFlow;
    private DecisionEngine engine;

    @BeforeEach
    void setUp() {
        FlowParser parser = new FlowParser();
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream("/flows/oauth_stepup_auth.yaml"),
                "Test flow resource /flows/oauth_stepup_auth.yaml not found"
        );
        this.stepUpFlow = parser.parseYaml(is);
        this.engine = new DecisionEngine();
    }

    @Test
    void testLowRiskFullFlowSimulation() {
        SessionContext initialContext = new SessionContext(Map.of(
                "userId", "user_alice",
                "ip", "192.168.1.100",
                "deviceId", "device_macbook_pro"
        ));

        Map<String, Object> decisions = Map.of(
                "fetch_user_profile", Map.of("name", "Alice", "status", "ACTIVE"),
                "check_device_risk", Map.of("riskScore", 15)
        );

        FlowSimulation sim = engine.simulate(stepUpFlow, initialContext, decisions);

        assertThat(sim.isCompleted()).isTrue();
        assertThat(sim.isSuccess()).isTrue();
        assertThat(sim.isDenied()).isFalse();
        assertThat(sim.getExecutionPath()).containsExactly("evaluate_auth", "issue_basic_token");
        assertThat(sim.getAllCommands()).hasSize(2);
        assertThat(sim.getAllScreens()).isEmpty();
        assertThat(sim.getTerminalResult().getClaims())
                .containsEntry("sub", "user_alice")
                .containsEntry("acr", "urn:pathfinder:auth:level1");

        String trace = sim.toVisualTrace();
        assertThat(trace).contains("=== Flow Simulation Trace: oauth-stepup-auth ===");
        assertThat(trace).contains("State: evaluate_auth");
        assertThat(trace).contains("State: issue_basic_token");
        assertThat(trace).contains("[Outcome] SUCCESS");
    }

    @Test
    void testHighRiskMfaFullFlowSimulationWithDecisions() {
        SessionContext initialContext = new SessionContext(Map.of(
                "userId", "user_bob",
                "ip", "203.0.113.42",
                "deviceId", "device_unrecognized"
        ));

        Map<String, Object> decisions = Map.of(
                "fetch_user_profile", Map.of("name", "Bob", "status", "ACTIVE"),
                "check_device_risk", Map.of("riskScore", 75),
                "otp_entry_screen", Map.of("otpCode", "123456"),
                "verify_code", Map.of("valid", true)
        );

        FlowSimulation sim = engine.simulate(stepUpFlow, initialContext, decisions);

        assertThat(sim.isCompleted()).isTrue();
        assertThat(sim.isSuccess()).isTrue();
        assertThat(sim.getExecutionPath()).containsExactly(
                "evaluate_auth",
                "trigger_stepup_mfa",
                "verify_otp",
                "issue_stepup_token"
        );
        assertThat(sim.getAllCommands()).hasSize(4); // fetch_user_profile, check_device_risk, send_otp_sms, verify_code
        assertThat(sim.getAllScreens()).hasSize(1);
        assertThat(sim.getAllScreens().get(0).getScreenId()).isEqualTo("otp_entry_screen");
        assertThat(sim.getTerminalResult().getClaims())
                .containsEntry("sub", "user_bob")
                .containsEntry("acr", "urn:pathfinder:auth:level2");

        String trace = sim.toVisualTrace();
        assertThat(trace).contains("State: trigger_stepup_mfa");
        assertThat(trace).contains("Screens (1):");
        assertThat(trace).contains("otp_entry_screen");
        assertThat(trace).contains("State: verify_otp");
        assertThat(trace).contains("[Outcome] SUCCESS");
    }

    @Test
    void testSimulationPausesAtCheckpointWhenDecisionIsMissing() {
        SessionContext initialContext = new SessionContext(Map.of(
                "userId", "user_bob",
                "ip", "203.0.113.42",
                "deviceId", "device_unrecognized"
        ));

        // High risk result provided, but NO MFA input provided
        Map<String, Object> decisions = Map.of(
                "fetch_user_profile", Map.of("name", "Bob", "status", "ACTIVE"),
                "check_device_risk", Map.of("riskScore", 75)
        );

        FlowSimulation sim = engine.simulate(stepUpFlow, initialContext, decisions);

        assertThat(sim.isCompleted()).isFalse();
        assertThat(sim.getTerminalResult()).isNull();
        assertThat(sim.getPausedCheckpoint()).isNotNull();
        assertThat(sim.getPausedCheckpoint().getResumeState()).isEqualTo("trigger_stepup_mfa");
        assertThat(sim.getPausedCheckpoint().getExpectedInputs()).contains("input");
        assertThat(sim.getExecutionPath()).containsExactly("evaluate_auth", "trigger_stepup_mfa");

        String trace = sim.toVisualTrace();
        assertThat(trace).contains("[Paused] Checkpoint at 'trigger_stepup_mfa'");
    }

    @Test
    void testClientConfigAndDataDrivenFlowSimulation() {
        String yaml = """
            id: client_policy_flow
            version: "1.0"
            initialState: EVALUATE_POLICY
            states:
              EVALUATE_POLICY:
                type: DECISION_FORK
                on:
                  - if: "data.blocked == true"
                    target: DENY_ACCESS
                  - if: "config.requireMfa == true || data.riskScore > 50"
                    target: MFA_CHALLENGE
                  - default: true
                    target: GRANT_ACCESS
              MFA_CHALLENGE:
                type: FRONTEND
                schemas:
                  - screenId: otp_screen
                    title: Enter OTP
                    jsonSchema:
                      type: object
                      properties:
                        otp:
                          type: string
                on:
                  - if: "input.otp == '123456'"
                    target: GRANT_ACCESS
                  - default: true
                    target: DENY_ACCESS
              GRANT_ACCESS:
                type: TERMINAL
                terminal:
                  status: SUCCESS
                  claims:
                    client_id: "${config.clientId}"
                    user: "${data.username}"
              DENY_ACCESS:
                type: TERMINAL
                terminal:
                  status: DENIED
                  error: "Access denied by client policy"
            """;

        FlowDefinition policyFlow = new FlowParser().parseYaml(yaml);

        // Scenario 1: Low risk, client config requireMfa: false -> Direct Grant
        SessionContext sessionLowRisk = new SessionContext(
                Map.of("username", "alice", "riskScore", 10, "blocked", false),
                Map.of("clientId", "mobile-app-client", "requireMfa", false)
        );
        FlowSimulation sim1 = engine.simulate(policyFlow, sessionLowRisk, Map.of());
        assertThat(sim1.isCompleted()).isTrue();
        assertThat(sim1.isSuccess()).isTrue();
        assertThat(sim1.getExecutionPath()).containsExactly("EVALUATE_POLICY", "GRANT_ACCESS");
        assertThat(sim1.getTerminalResult().getClaims())
                .containsEntry("client_id", "mobile-app-client")
                .containsEntry("user", "alice");

        // Scenario 2: Low risk, but client config requireMfa: true -> Routes through MFA
        SessionContext sessionRequireMfa = new SessionContext(
                Map.of("username", "alice", "riskScore", 10, "blocked", false),
                Map.of("clientId", "banking-portal-client", "requireMfa", true)
        );
        FlowSimulation sim2 = engine.simulate(policyFlow, sessionRequireMfa, Map.of("otp", "123456"));
        assertThat(sim2.isCompleted()).isTrue();
        assertThat(sim2.isSuccess()).isTrue();
        assertThat(sim2.getExecutionPath()).containsExactly("EVALUATE_POLICY", "MFA_CHALLENGE", "GRANT_ACCESS");
        assertThat(sim2.getAllScreens()).hasSize(1);
        assertThat(sim2.getAllScreens().get(0).getScreenId()).isEqualTo("otp_screen");

        // Scenario 3: Blocked user in data -> Immediate Deny
        SessionContext sessionBlocked = new SessionContext(
                Map.of("username", "bad_actor", "riskScore", 99, "blocked", true),
                Map.of("clientId", "mobile-app-client", "requireMfa", false)
        );
        FlowSimulation sim3 = engine.simulate(policyFlow, sessionBlocked, Map.of());
        assertThat(sim3.isCompleted()).isTrue();
        assertThat(sim3.isDenied()).isTrue();
        assertThat(sim3.getExecutionPath()).containsExactly("EVALUATE_POLICY", "DENY_ACCESS");
        assertThat(sim3.getTerminalResult().getError()).isEqualTo("Access denied by client policy");
    }
}
