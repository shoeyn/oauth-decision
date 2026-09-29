package io.pathfinder.engine.core;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.parser.FlowParser;
import io.pathfinder.engine.registry.ClasspathFlowRegistry;
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

    @Test
    void testSubflowSimulationFromYamlFiles() {
        ClasspathFlowRegistry registry = new ClasspathFlowRegistry()
                .withResource("/flows/parent_login_flow.yaml")
                .withResource("/flows/mfa_totp_subflow.yaml");

        DecisionEngine subflowEngine = new DecisionEngine(registry);

        // Case 1: requireMfa == true -> delegates to subflow, subflow succeeds, returns to parent
        SessionContext sessionMfa = new SessionContext(
                Map.of("userId", "user_carol", "riskScore", 10),
                Map.of("clientId", "fintech_client_1", "requireMfa", true)
        );

        Map<String, Object> decisions = Map.of(
                "totpCode", "123456",
                "verify_totp_code", Map.of("valid", true)
        );

        FlowSimulation sim = subflowEngine.simulate("parent_login_flow", sessionMfa, decisions);

        assertThat(sim.isCompleted()).isTrue();
        assertThat(sim.isSuccess()).isTrue();
        assertThat(sim.getExecutionPath()).containsExactly(
                "evaluate_policy",
                "delegate_to_mfa",
                "prompt_totp",
                "verify_totp",
                "totp_success",
                "issue_tokens"
        );
        assertThat(sim.getAllScreens()).hasSize(1);
        assertThat(sim.getAllScreens().get(0).getScreenId()).isEqualTo("totp_screen");
        assertThat(sim.getAllCommands()).hasSize(1);
        assertThat(sim.getAllCommands().get(0).getStepId()).isEqualTo("verify_totp_code");
        assertThat(sim.getTerminalResult().getClaims())
                .containsEntry("sub", "user_carol")
                .containsEntry("client_id", "fintech_client_1")
                .containsEntry("acr", "urn:pathfinder:auth:level2");

        String trace = sim.toVisualTrace();
        assertThat(trace).contains("=== Flow Simulation Trace: parent_login_flow ===");
        assertThat(trace).contains("State: delegate_to_mfa (SUBFLOW)");
        assertThat(trace).contains("Transition ➔ subflow:mfa_totp_subflow");
        assertThat(trace).contains("State: prompt_totp (FRONTEND)");
        assertThat(trace).contains("State: verify_totp (BACKEND)");
        assertThat(trace).contains("State: totp_success (TERMINAL)");
        assertThat(trace).contains("Transition ➔ return_to_parent");
        assertThat(trace).contains("State: issue_tokens (TERMINAL)");
        assertThat(trace).contains("[Outcome] SUCCESS");

        // Case 2: subflow denied -> returns to parent, branches to mfa_rejected
        Map<String, Object> failedDecisions = Map.of(
                "totpCode", "000000",
                "verify_totp_code", Map.of("valid", false)
        );
        FlowSimulation failedSim = subflowEngine.simulate("parent_login_flow", sessionMfa, failedDecisions);
        assertThat(failedSim.isCompleted()).isTrue();
        assertThat(failedSim.isDenied()).isTrue();
        assertThat(failedSim.getExecutionPath()).containsExactly(
                "evaluate_policy",
                "delegate_to_mfa",
                "prompt_totp",
                "verify_totp",
                "totp_failed",
                "mfa_rejected"
        );
        assertThat(failedSim.getTerminalResult().getError()).isEqualTo("Multi-factor authentication was unsuccessful.");

        // Case 3: subflow paused at checkpoint inside subflow (missing TOTP code)
        FlowSimulation pausedSim = subflowEngine.simulate("parent_login_flow", sessionMfa, Map.of());
        assertThat(pausedSim.isCompleted()).isFalse();
        assertThat(pausedSim.getPausedCheckpoint()).isNotNull();
        assertThat(pausedSim.getPausedCheckpoint().getFlowId()).isEqualTo("mfa_totp_subflow");
        assertThat(pausedSim.getPausedCheckpoint().getResumeState()).isEqualTo("prompt_totp");
        assertThat(pausedSim.getExecutionPath()).containsExactly(
                "evaluate_policy",
                "delegate_to_mfa",
                "prompt_totp"
        );

        // Case 4: low risk and requireMfa == false -> subflow completely bypassed
        SessionContext sessionNoMfa = new SessionContext(
                Map.of("userId", "user_dave", "riskScore", 5),
                Map.of("clientId", "mobile_client", "requireMfa", false)
        );
        FlowSimulation bypassedSim = subflowEngine.simulate("parent_login_flow", sessionNoMfa, Map.of());
        assertThat(bypassedSim.isCompleted()).isTrue();
        assertThat(bypassedSim.isSuccess()).isTrue();
        assertThat(bypassedSim.getExecutionPath()).containsExactly(
                "evaluate_policy",
                "issue_tokens"
        );
    }

    @Test
    void testRedirectDropoutWithFailureUrl() {
        String yaml = """
            id: consent_dropout_flow
            initialState: ask_consent
            states:
              ask_consent:
                type: FRONTEND
                schemas:
                  - screenId: consent_screen
                    title: Application Consent
                on:
                  - if: "input.approved == true"
                    target: issue_code
                  - default: true
                    target: cancel_redirect
              issue_code:
                type: TERMINAL
                terminal:
                  status: SUCCESS
                  redirectUrl: "${config.redirectUri}?code=AUTH_123&state=${data.state}"
              cancel_redirect:
                type: TERMINAL
                terminal:
                  status: DENIED
                  error: access_denied
                  errorDescription: "User refused consent prompt."
                  redirectUrl: "${config.redirectUri}?error=access_denied&state=${data.state}"
            """;

        FlowDefinition flow = new FlowParser().parseYaml(yaml);

        SessionContext context = new SessionContext(
                Map.of("state", "client_state_xyz789"),
                Map.of("redirectUri", "https://client.example.com/callback")
        );

        // Scenario: User clicks Deny / Cancel
        Map<String, Object> decisions = Map.of("consent_screen", Map.of("approved", false));

        FlowSimulation sim = engine.simulate(flow, context, decisions);

        assertThat(sim.isCompleted()).isTrue();
        assertThat(sim.isDenied()).isTrue();
        assertThat(sim.getExecutionPath()).containsExactly("ask_consent", "cancel_redirect");

        TerminalResult result = sim.getTerminalResult();
        assertThat(result.isRedirect()).isTrue();
        assertThat(result.isUiDropout()).isFalse();
        assertThat(result.getError()).isEqualTo("access_denied");
        assertThat(result.getErrorDescription()).isEqualTo("User refused consent prompt.");
        assertThat(result.getRedirectUrl()).isEqualTo("https://client.example.com/callback?error=access_denied&state=client_state_xyz789");

        String trace = sim.toVisualTrace();
        assertThat(trace).contains("[Outcome] DENIED (Error: access_denied: User refused consent prompt.)");
        assertThat(trace).contains("[Redirect ➔ https://client.example.com/callback?error=access_denied&state=client_state_xyz789]");
    }

    @Test
    void testHardUiDropoutStayingInUi() {
        String yaml = """
            id: fraud_lockout_flow
            initialState: assess_risk
            states:
              assess_risk:
                type: BACKEND
                commands:
                  - id: check_fraud
                    service: fraud-engine
                on:
                  - if: "results.check_fraud.score > 80"
                    target: security_lockout
                  - default: true
                    target: proceed_login
              proceed_login:
                type: TERMINAL
                terminal:
                  status: SUCCESS
              security_lockout:
                type: TERMINAL
                schemas:
                  - screenId: lockout_screen
                    title: "Security Lockout"
                    description: "Your session has been terminated due to high risk activity."
                terminal:
                  status: DENIED
                  error: fraud_detected
                  errorDescription: "Suspicious activity detected from IP address."
            """;

        FlowDefinition flow = new FlowParser().parseYaml(yaml);

        SessionContext context = new SessionContext(
                Map.of("ip", "203.0.113.199"),
                Map.of("clientId", "bank_portal")
        );

        Map<String, Object> decisions = Map.of("check_fraud", Map.of("score", 95));

        FlowSimulation sim = engine.simulate(flow, context, decisions);

        assertThat(sim.isCompleted()).isTrue();
        assertThat(sim.isDenied()).isTrue();
        assertThat(sim.getExecutionPath()).containsExactly("assess_risk", "security_lockout");

        TerminalResult result = sim.getTerminalResult();
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.isUiDropout()).isTrue();
        assertThat(result.getError()).isEqualTo("fraud_detected");
        assertThat(result.getErrorDescription()).isEqualTo("Suspicious activity detected from IP address.");
        assertThat(result.getRedirectUrl()).isNull();

        // Verifies the terminal UI screen was recorded and emitted
        assertThat(sim.getAllScreens()).hasSize(1);
        assertThat(sim.getAllScreens().get(0).getScreenId()).isEqualTo("lockout_screen");
        assertThat(sim.getAllScreens().get(0).getTitle()).isEqualTo("Security Lockout");

        String trace = sim.toVisualTrace();
        assertThat(trace).contains("State: security_lockout (TERMINAL)");
        assertThat(trace).contains("[Outcome] DENIED (Error: fraud_detected: Suspicious activity detected from IP address.)");
        assertThat(trace).contains("[UI Dropout: Stays in UI]");
    }
}
