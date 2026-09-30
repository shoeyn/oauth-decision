package io.pathfinder.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.pathfinder.engine.execution.state.BackendStep;
import io.pathfinder.engine.execution.state.Event;
import io.pathfinder.engine.execution.state.ExecutionPlan;
import io.pathfinder.engine.execution.state.FrontendStep;
import io.pathfinder.engine.execution.state.SessionContext;
import io.pathfinder.engine.execution.state.TerminalResult;
import io.pathfinder.engine.flow.model.FlowDefinition;
import io.pathfinder.engine.flow.parser.FlowParser;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DecisionEngineSimulationTest {
    private FlowDefinition flow;
    private DecisionEngine engine;

    @BeforeEach
    void setUp() {
        FlowParser parser = new FlowParser();
        InputStream is = java.util.Objects.requireNonNull(
                getClass().getResourceAsStream("/flows/oauth_stepup_auth.yaml"),
                "Test flow resource /flows/oauth_stepup_auth.yaml not found"
        );
        this.flow = parser.parseYaml(is);
        this.engine = new DecisionEngine();
    }

    @Test
    void testLowRiskDirectAuthenticationFlow() {
        // --- Turn 1: Initial OAuth Request ---
        SessionContext session = new SessionContext(Map.of(
                "userId", "user_alice",
                "ip", "192.168.1.100",
                "deviceId", "device_macbook_pro"
        ));

        ExecutionPlan turn1Plan = engine.evaluate(flow, null, session, Event.start());

        assertThat(turn1Plan.isTerminal()).isFalse();
        assertThat(turn1Plan.hasCheckpoint()).isTrue();
        assertThat(turn1Plan.getCheckpoint().getResumeState()).isEqualTo("evaluate_auth");
        // Bundled 2 backend commands together
        assertThat(turn1Plan.getBackendSteps()).hasSize(2);
        assertThat(turn1Plan.getBackendSteps().get(0).getService()).isEqualTo("user-directory");
        assertThat(turn1Plan.getBackendSteps().get(1).getService()).isEqualTo("fraud-engine");
        assertThat(turn1Plan.getBackendSteps().get(1).getPayload()).containsEntry("ip", "192.168.1.100");

        // --- Host executes the 2 backend tasks in parallel and gathers results ---
        Map<String, Object> backendResults = Map.of(
                "fetch_user_profile", Map.of("name", "Alice", "status", "ACTIVE"),
                "check_device_risk", Map.of("riskScore", 15L) // Low risk
        );

        // --- Turn 2: Host resumes checkpoint with results ---
        ExecutionPlan turn2Plan = engine.evaluate(
                flow,
                turn1Plan.getCheckpoint().getResumeState(),
                turn1Plan.getUpdatedContext(),
                Event.resume(backendResults)
        );

        // Low risk (< 30) branches directly to issue_basic_token
        assertThat(turn2Plan.isTerminal()).isTrue();
        TerminalResult terminal = turn2Plan.getTerminal();
        assertThat(terminal.getStatus()).isEqualTo("SUCCESS");
        assertThat(terminal.getClaims()).containsEntry("sub", "user_alice");
        assertThat(terminal.getClaims()).containsEntry("acr", "urn:pathfinder:auth:level1");
        assertThat(terminal.getClaims().get("amr")).isEqualTo(List.of("pwd"));
    }

    @Test
    void testHighRiskStepUpMfaFlowWithBundledBackendAndFrontendSteps() {
        // --- Turn 1: Initial OAuth Request ---
        SessionContext session = new SessionContext(Map.of(
                "userId", "user_bob",
                "ip", "203.0.113.42",
                "deviceId", "device_unrecognized"
        ));

        ExecutionPlan turn1Plan = engine.evaluate(flow, null, session, Event.start());
        assertThat(turn1Plan.getBackendSteps()).hasSize(2);

        // --- Host executes backend calls; risk score is 75 (High risk) ---
        Map<String, Object> backendResults = Map.of(
                "fetch_user_profile", Map.of("name", "Bob", "status", "ACTIVE"),
                "check_device_risk", Map.of("riskScore", 75L)
        );

        // --- Turn 2: Host resumes checkpoint with high risk result ---
        ExecutionPlan turn2Plan = engine.evaluate(
                flow,
                turn1Plan.getCheckpoint().getResumeState(),
                turn1Plan.getUpdatedContext(),
                Event.resume(backendResults)
        );

        // Verification of BUNDLED BACKEND + FRONTEND steps in a single plan:
        assertThat(turn2Plan.isTerminal()).isFalse();
        assertThat(turn2Plan.hasBackendSteps()).isTrue();
        assertThat(turn2Plan.hasFrontendSteps()).isTrue();

        // 1. Backend step: Host triggers SMS in background
        assertThat(turn2Plan.getBackendSteps()).hasSize(1);
        BackendStep smsStep = turn2Plan.getBackendSteps().get(0);
        assertThat(smsStep.getStepId()).isEqualTo("send_otp_sms");
        assertThat(smsStep.getService()).isEqualTo("notification-service");
        assertThat(smsStep.getPayload()).containsEntry("userId", "user_bob");

        // 2. Frontend step: Ruby UI immediately renders OTP form via JSON Schema + UI Schema
        assertThat(turn2Plan.getFrontendSteps()).hasSize(1);
        FrontendStep otpScreen = turn2Plan.getFrontendSteps().get(0);
        assertThat(otpScreen.getScreenId()).isEqualTo("otp_entry_screen");
        assertThat(otpScreen.getTitle()).isEqualTo("Two-Factor Verification Required");
        assertThat(otpScreen.getJsonSchema().get("properties").get("otpCode").get("pattern").asString()).isEqualTo("^[0-9]{6}$");
        assertThat(otpScreen.getUiSchema().get("otpCode").get("ui:widget").asString()).isEqualTo("otp");

        // 3. Checkpoint: Host must come back once user submits OTP
        assertThat(turn2Plan.hasCheckpoint()).isTrue();
        assertThat(turn2Plan.getCheckpoint().getResumeState()).isEqualTo("trigger_stepup_mfa");

        // --- Turn 3: Ruby Frontend submits INVALID OTP (non-numeric / too short) ---
        ExecutionPlan turn3InvalidPlan = engine.evaluate(
                flow,
                turn2Plan.getCheckpoint().getResumeState(),
                turn2Plan.getUpdatedContext(),
                Event.submit(Map.of("otpCode", "abc"))
        );

        // Engine catches schema error and rejects transition
        assertThat(turn3InvalidPlan.isTerminal()).isFalse();
        assertThat(turn3InvalidPlan.getFrontendSteps()).hasSize(1);
        assertThat(turn3InvalidPlan.getFrontendSteps().get(0).hasErrors()).isTrue();
        assertThat(turn3InvalidPlan.getFrontendSteps().get(0).getValidationErrors().get(0))
                .contains("does not match the regex pattern ^[0-9]{6}$");
        assertThat(turn3InvalidPlan.getCheckpoint().getResumeState()).isEqualTo("trigger_stepup_mfa");

        // --- Turn 4: Ruby Frontend submits VALID OTP ("123456") ---
        ExecutionPlan turn4ValidPlan = engine.evaluate(
                flow,
                turn3InvalidPlan.getCheckpoint().getResumeState(),
                turn3InvalidPlan.getUpdatedContext(),
                Event.submit(Map.of("otpCode", "123456"))
        );

        // Validation passes -> Transitions to verify_otp
        assertThat(turn4ValidPlan.isTerminal()).isFalse();
        assertThat(turn4ValidPlan.getBackendSteps()).hasSize(1);
        BackendStep verifyCmd = turn4ValidPlan.getBackendSteps().get(0);
        assertThat(verifyCmd.getStepId()).isEqualTo("verify_code");
        assertThat(verifyCmd.getService()).isEqualTo("otp-service");
        assertThat(verifyCmd.getPayload()).containsEntry("code", "123456");
        assertThat(turn4ValidPlan.getCheckpoint().getResumeState()).isEqualTo("verify_otp");

        // --- Turn 5: Host executes OTP verification service (valid: true) ---
        ExecutionPlan turn5CompletePlan = engine.evaluate(
                flow,
                turn4ValidPlan.getCheckpoint().getResumeState(),
                turn4ValidPlan.getUpdatedContext(),
                Event.resume(Map.of("verify_code", Map.of("valid", true)))
        );

        // Final completion with Step-up OAuth claims!
        assertThat(turn5CompletePlan.isTerminal()).isTrue();
        TerminalResult finalTerminal = turn5CompletePlan.getTerminal();
        assertThat(finalTerminal.getStatus()).isEqualTo("SUCCESS");
        assertThat(finalTerminal.getClaims()).containsEntry("sub", "user_bob");
        assertThat(finalTerminal.getClaims()).containsEntry("acr", "urn:pathfinder:auth:level2");
        assertThat(finalTerminal.getClaims().get("amr")).isEqualTo(List.of("pwd", "otp"));
    }
}
