package io.pathfinder.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.pathfinder.engine.execution.state.Event;
import io.pathfinder.engine.execution.state.ExecutionPlan;
import io.pathfinder.engine.execution.state.SessionContext;
import io.pathfinder.engine.flow.model.FlowDefinition;
import io.pathfinder.engine.flow.parser.FlowParser;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StepBypassingAndShortCircuitTest {
    private FlowParser parser;
    private DecisionEngine engine;

    @BeforeEach
    void setUp() {
        this.parser = new FlowParser();
        this.engine = new DecisionEngine();
    }

    private static final String RISK_BASED_BYPASS_YAML = """
            id: risk_bypass_flow
            initialState: assess_risk
            states:
              assess_risk:
                type: BACKEND
                commands:
                  - id: run_fraud_check
                    service: fraud-engine
                    payload:
                      ip: "${context.ip}"
                on:
                  - if: "results.run_fraud_check.isBlocked == true"
                    target: immediate_lockout
                  - if: "results.run_fraud_check.riskScore < 20"
                    target: issue_tokens
                  - default: true
                    target: prompt_mfa

              prompt_mfa:
                type: FRONTEND
                schemas:
                  - screenId: mfa_screen
                    title: "MFA Required"
                on:
                  - event: SUBMIT
                    target: verify_mfa

              verify_mfa:
                type: BACKEND
                commands:
                  - id: verify_code
                    service: otp-service
                on:
                  - if: "results.verify_code.valid == true"
                    target: issue_tokens
                  - default: true
                    target: immediate_lockout

              issue_tokens:
                type: TERMINAL
                terminal:
                  status: SUCCESS
                  claims:
                    sub: "${context.userId}"
                    flowMode: "FAST_PATH"

              immediate_lockout:
                type: TERMINAL
                terminal:
                  status: DENIED
                  error: "Access blocked due to high fraud risk"
            """;

    @Test
    void testLowRiskUserCompletelyBypassesMfaSteps() {
        FlowDefinition flow = parser.parseYaml(RISK_BASED_BYPASS_YAML);

        // Turn 1: Evaluate assess_risk (accumulates fraud check command)
        ExecutionPlan turn1 = engine.evaluate(
                flow,
                null,
                new SessionContext(Map.of("ip", "10.0.0.1", "userId", "trusted_alice")),
                Event.start()
        );

        assertThat(turn1.isTerminal()).isFalse();
        assertThat(turn1.getBackendSteps()).hasSize(1);
        assertThat(turn1.getBackendSteps().get(0).getStepId()).isEqualTo("run_fraud_check");
        assertThat(turn1.getFrontendSteps()).isEmpty();

        // Turn 2: Backend returns low risk (score: 10, isBlocked: false)
        ExecutionPlan turn2 = engine.evaluate(
                flow,
                turn1.getCheckpoint().getResumeState(),
                turn1.getUpdatedContext(),
                Event.resume(Map.of("run_fraud_check", Map.of("riskScore", 10L, "isBlocked", false)))
        );

        // PROVE: Bypassed intermediate prompt_mfa and verify_mfa states entirely!
        assertThat(turn2.isTerminal()).isTrue();
        assertThat(turn2.getTerminal().getStatus()).isEqualTo("SUCCESS");
        assertThat(turn2.getTerminal().getClaims()).containsEntry("flowMode", "FAST_PATH");
        assertThat(turn2.getTerminal().getClaims()).containsEntry("sub", "trusted_alice");

        // PROVE: Zero frontend steps and zero MFA backend commands were ever emitted
        assertThat(turn2.hasFrontendSteps()).isFalse();
        assertThat(turn2.hasBackendSteps()).isFalse();
        assertThat(turn2.getFrontendSteps()).isEmpty();
        assertThat(turn2.getBackendSteps()).isEmpty();

        // PROVE: Execution history / breadcrumbs visited ONLY assess_risk -> issue_tokens
        assertThat(turn2.getUpdatedContext().getHistory()).containsExactly("assess_risk", "issue_tokens");
    }

    @Test
    void testBlockedUserFailsFastWithoutReachingMfaOrTokenIssuance() {
        FlowDefinition flow = parser.parseYaml(RISK_BASED_BYPASS_YAML);

        // Turn 1: Run fraud check
        ExecutionPlan turn1 = engine.evaluate(
                flow,
                null,
                new SessionContext(Map.of("ip", "198.51.100.2", "userId", "hacker_dave")),
                Event.start()
        );

        // Turn 2: Fraud engine reports isBlocked: true
        ExecutionPlan turn2 = engine.evaluate(
                flow,
                turn1.getCheckpoint().getResumeState(),
                turn1.getUpdatedContext(),
                Event.resume(Map.of("run_fraud_check", Map.of("riskScore", 99L, "isBlocked", true)))
        );

        // PROVE: Short-circuits directly to immediate_lockout
        assertThat(turn2.isTerminal()).isTrue();
        assertThat(turn2.getTerminal().getStatus()).isEqualTo("DENIED");
        assertThat(turn2.getTerminal().getError()).isEqualTo("Access blocked due to high fraud risk");

        // PROVE: History contains only assess_risk -> immediate_lockout
        assertThat(turn2.getUpdatedContext().getHistory()).containsExactly("assess_risk", "immediate_lockout");
    }

    @Test
    void testUserDrivenOptOutBypassesOptionalSubflow() {
        String optOutYaml = """
                id: optional_biometrics_flow
                initialState: prompt_choice
                states:
                  prompt_choice:
                    type: FRONTEND
                    schemas:
                      - screenId: choice_screen
                        title: "Enable Passkey?"
                    on:
                      - if: "input.action == 'SKIP'"
                        target: standard_success
                      - default: true
                        target: enroll_passkey_subflow

                  enroll_passkey_subflow:
                    type: SUBFLOW
                    subflow: dummy_passkey_subflow
                    on:
                      - outcome: SUCCESS
                        target: biometric_success
                      - default: true
                        target: standard_success

                  standard_success:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        authLevel: "STANDARD_PASSWORD"

                  biometric_success:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        authLevel: "BIOMETRIC_ENROLLED"
                """;

        FlowDefinition flow = parser.parseYaml(optOutYaml);

        // Turn 1: Shows choice screen
        ExecutionPlan turn1 = engine.evaluate(flow, null, new SessionContext(), Event.start());
        assertThat(turn1.getFrontendSteps()).hasSize(1);
        assertThat(turn1.getFrontendSteps().get(0).getScreenId()).isEqualTo("choice_screen");

        // Turn 2: User explicitly selects "SKIP"
        ExecutionPlan turn2 = engine.evaluate(
                flow,
                turn1.getCheckpoint().getResumeState(),
                turn1.getUpdatedContext(),
                Event.submit(Map.of("action", "SKIP"))
        );

        // PROVE: Subflow was completely bypassed
        assertThat(turn2.isTerminal()).isTrue();
        assertThat(turn2.getTerminal().getStatus()).isEqualTo("SUCCESS");
        assertThat(turn2.getTerminal().getClaims()).containsEntry("authLevel", "STANDARD_PASSWORD");
        assertThat(turn2.getUpdatedContext().hasCallStack()).isFalse();
        assertThat(turn2.getUpdatedContext().getHistory()).containsExactly("prompt_choice", "standard_success");
    }

    @Test
    void testContextFlagBypassesStepupMfa() {
        String contextBypassYaml = """
                id: context_bypass_flow
                initialState: check_device_trust
                states:
                  check_device_trust:
                    type: DECISION_FORK
                    on:
                      - if: "context.isDeviceTrusted == true"
                        target: trusted_access
                      - default: true
                        target: require_stepup

                  require_stepup:
                    type: FRONTEND
                    schemas:
                      - screenId: stepup_screen
                    on:
                      - event: SUBMIT
                        target: trusted_access

                  trusted_access:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        sessionType: "TRUSTED_DEVICE"
                """;

        FlowDefinition flow = parser.parseYaml(contextBypassYaml);

        // User arrives with isDeviceTrusted: true in session context
        SessionContext session = new SessionContext(Map.of("isDeviceTrusted", true));
        ExecutionPlan plan = engine.evaluate(flow, null, session, Event.start());

        // PROVE: Reaches terminal in turn 1 without pausing or rendering stepup_screen
        assertThat(plan.isTerminal()).isTrue();
        assertThat(plan.hasCheckpoint()).isFalse();
        assertThat(plan.hasFrontendSteps()).isFalse();
        assertThat(plan.getTerminal().getStatus()).isEqualTo("SUCCESS");
        assertThat(plan.getTerminal().getClaims()).containsEntry("sessionType", "TRUSTED_DEVICE");
        assertThat(plan.getUpdatedContext().getHistory()).containsExactly("check_device_trust", "trusted_access");
    }
}
