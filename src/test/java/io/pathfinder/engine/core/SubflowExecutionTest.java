package io.pathfinder.engine.core;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.parser.FlowParser;
import io.pathfinder.engine.registry.FlowRegistry;
import io.pathfinder.engine.registry.InMemoryFlowRegistry;
import io.pathfinder.engine.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubflowExecutionTest {
    private FlowParser parser;
    private FlowRegistry registry;
    private DecisionEngine engine;

    @BeforeEach
    void setUp() {
        this.parser = new FlowParser();
        this.registry = new InMemoryFlowRegistry();
        this.engine = new DecisionEngine(registry);
    }

    /**
     * Reusable MFA subflow used across different caller flows and branches.
     */
    private static final String REUSABLE_MFA_FLOW_YAML = """
            id: reusable_mfa_flow
            name: "Reusable MFA Flow"
            initialState: prompt_otp
            states:
              prompt_otp:
                type: FRONTEND
                schemas:
                  - screenId: otp_screen
                    title: "Enter OTP"
                    jsonSchema:
                      type: object
                      required: [otpCode]
                      properties:
                        otpCode:
                          type: string
                          pattern: "^[0-9]{6}$"
                    uiSchema:
                      otpCode:
                        "ui:widget": "otp"
                on:
                  - event: SUBMIT
                    target: verify_otp

              verify_otp:
                type: BACKEND
                commands:
                  - id: check_code
                    service: otp-service
                    payload:
                      code: "${context.otpCode}"
                      userId: "${context.userId}"
                on:
                  - if: "results.check_code.valid == true"
                    target: mfa_success
                    contextUpdates:
                      mfaMethod: "'SMS_OTP'"
                      mfaVerified: "true"
                  - default: true
                    target: mfa_denied

              mfa_success:
                type: TERMINAL
                terminal:
                  status: SUCCESS
                  claims:
                    mfa_status: "VERIFIED"

              mfa_denied:
                type: TERMINAL
                terminal:
                  status: DENIED
                  error: "Invalid one-time passcode"
            """;

    @Test
    void testReusableSubflowCalledFromLoginBranch_ReturnsToLoginOutcome() {
        // Main flow with two distinct call points using the exact same reusable subflow:
        // 1. high-risk login -> require_login_mfa -> (mfa_flow) -> issue_login_token
        // 2. high-value transfer -> require_transfer_mfa -> (mfa_flow) -> execute_transfer
        String multiCallerFlowYaml = """
                id: multi_action_flow
                initialState: route_action
                states:
                  route_action:
                    type: DECISION_FORK
                    on:
                      - if: "context.action == 'LOGIN'"
                        target: require_login_mfa
                      - if: "context.action == 'TRANSFER'"
                        target: require_transfer_mfa
                      - default: true
                        target: invalid_action

                  require_login_mfa:
                    type: SUBFLOW
                    subflow: reusable_mfa_flow
                    on:
                      - outcome: SUCCESS
                        target: issue_login_token
                      - outcome: DENIED
                        target: login_failed

                  require_transfer_mfa:
                    type: SUBFLOW
                    subflow: reusable_mfa_flow
                    on:
                      - outcome: SUCCESS
                        target: execute_transfer
                      - outcome: DENIED
                        target: transfer_rejected

                  issue_login_token:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        sub: "${context.userId}"
                        scope: "login:authorized"
                        mfa_used: "${context.mfaMethod}"

                  login_failed:
                    type: TERMINAL
                    terminal:
                      status: DENIED
                      error: "Login MFA failed"

                  execute_transfer:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        sub: "${context.userId}"
                        transferStatus: "DISPATCHED"
                        amount: "${context.amount}"

                  transfer_rejected:
                    type: TERMINAL
                    terminal:
                      status: DENIED
                      error: "Transfer MFA rejected"

                  invalid_action:
                    type: TERMINAL
                    terminal:
                      status: DENIED
                      error: "Unknown action"
                """;

        FlowDefinition mainFlow = parser.parseYaml(multiCallerFlowYaml);
        FlowDefinition mfaFlow = parser.parseYaml(REUSABLE_MFA_FLOW_YAML);
        registry.register(mainFlow);
        registry.register(mfaFlow);

        // =========================================================================
        // CASE A: User initiates LOGIN -> hits require_login_mfa -> calls reusable_mfa_flow
        // =========================================================================
        SessionContext loginSession = new SessionContext(Map.of(
                "action", "LOGIN",
                "userId", "user_alice"
        ));

        // Turn 1: Engine starts at route_action -> enters require_login_mfa (SUBFLOW) -> enters reusable_mfa_flow
        ExecutionPlan turn1 = engine.evaluate(mainFlow, null, loginSession, Event.start());

        assertThat(turn1.isTerminal()).isFalse();
        assertThat(turn1.hasCheckpoint()).isTrue();
        assertThat(turn1.getCheckpoint().getResumeState()).isEqualTo("prompt_otp");
        assertThat(turn1.getCheckpoint().getFlowId()).isEqualTo("reusable_mfa_flow");
        // Verify call stack points back to require_login_mfa in multi_action_flow
        assertThat(turn1.getUpdatedContext().hasCallStack()).isTrue();
        assertThat(turn1.getUpdatedContext().peekFrame().getFlowId()).isEqualTo("multi_action_flow");
        assertThat(turn1.getUpdatedContext().peekFrame().getReturnStateId()).isEqualTo("require_login_mfa");

        // Turn 2: User submits valid OTP "654321"
        ExecutionPlan turn2 = engine.evaluate(
                mainFlow,
                turn1.getCheckpoint().getResumeState(),
                turn1.getUpdatedContext(),
                Event.submit(Map.of("otpCode", "654321"))
        );

        assertThat(turn2.isTerminal()).isFalse();
        assertThat(turn2.hasBackendSteps()).isTrue();
        assertThat(turn2.getBackendSteps().get(0).getStepId()).isEqualTo("check_code");
        // Shared session: subflow can read context.userId populated by parent
        assertThat(turn2.getBackendSteps().get(0).getPayload()).containsEntry("userId", "user_alice");
        assertThat(turn2.getBackendSteps().get(0).getPayload()).containsEntry("code", "654321");

        // Turn 3: Host executes check_code (valid: true) -> subflow hits mfa_success (SUCCESS)
        // Subflow returns to require_login_mfa -> transitions to issue_login_token!
        ExecutionPlan turn3 = engine.evaluate(
                mainFlow,
                turn2.getCheckpoint().getResumeState(),
                turn2.getUpdatedContext(),
                Event.resume(Map.of("check_code", Map.of("valid", true)))
        );

        assertThat(turn3.isTerminal()).isTrue();
        assertThat(turn3.getTerminal().getStatus()).isEqualTo("SUCCESS");
        // PROVE it returned to the LOGIN branch target:
        assertThat(turn3.getTerminal().getClaims()).containsEntry("scope", "login:authorized");
        assertThat(turn3.getTerminal().getClaims()).containsEntry("sub", "user_alice");
        // PROVE context updates from subflow propagated to parent session:
        assertThat(turn3.getTerminal().getClaims()).containsEntry("mfa_used", "SMS_OTP");
        assertThat(turn3.getUpdatedContext().getData()).containsEntry("mfaVerified", true);
        assertThat(turn3.getUpdatedContext().hasCallStack()).isFalse();

        // =========================================================================
        // CASE B: User initiates TRANSFER -> hits require_transfer_mfa -> calls EXACT SAME subflow
        // =========================================================================
        SessionContext transferSession = new SessionContext(Map.of(
                "action", "TRANSFER",
                "userId", "user_bob",
                "amount", "5000"
        ));

        // Turn 1: Enters require_transfer_mfa -> enters reusable_mfa_flow
        ExecutionPlan tTurn1 = engine.evaluate(mainFlow, null, transferSession, Event.start());

        assertThat(tTurn1.getCheckpoint().getResumeState()).isEqualTo("prompt_otp");
        // Verify call stack points back to require_transfer_mfa (DIFFERENT CALLER!)
        assertThat(tTurn1.getUpdatedContext().peekFrame().getReturnStateId()).isEqualTo("require_transfer_mfa");

        // Turn 2: User submits OTP
        ExecutionPlan tTurn2 = engine.evaluate(
                mainFlow,
                tTurn1.getCheckpoint().getResumeState(),
                tTurn1.getUpdatedContext(),
                Event.submit(Map.of("otpCode", "999888"))
        );

        // Turn 3: Backend check succeeds -> subflow finishes -> RETURNS TO execute_transfer!
        ExecutionPlan tTurn3 = engine.evaluate(
                mainFlow,
                tTurn2.getCheckpoint().getResumeState(),
                tTurn2.getUpdatedContext(),
                Event.resume(Map.of("check_code", Map.of("valid", true)))
        );

        assertThat(tTurn3.isTerminal()).isTrue();
        assertThat(tTurn3.getTerminal().getStatus()).isEqualTo("SUCCESS");
        // PROVE it returned to the TRANSFER branch target (not login!):
        assertThat(tTurn3.getTerminal().getClaims()).containsEntry("transferStatus", "DISPATCHED");
        assertThat(tTurn3.getTerminal().getClaims()).containsEntry("amount", "5000");
        assertThat(tTurn3.getTerminal().getClaims()).containsEntry("sub", "user_bob");

        // =========================================================================
        // CASE C: User fails MFA during TRANSFER -> returns to transfer_rejected!
        // =========================================================================
        ExecutionPlan failTurn1 = engine.evaluate(mainFlow, null, transferSession, Event.start());
        ExecutionPlan failTurn2 = engine.evaluate(
                mainFlow,
                failTurn1.getCheckpoint().getResumeState(),
                failTurn1.getUpdatedContext(),
                Event.submit(Map.of("otpCode", "000000"))
        );
        // OTP check fails
        ExecutionPlan failTurn3 = engine.evaluate(
                mainFlow,
                failTurn2.getCheckpoint().getResumeState(),
                failTurn2.getUpdatedContext(),
                Event.resume(Map.of("check_code", Map.of("valid", false)))
        );

        assertThat(failTurn3.isTerminal()).isTrue();
        assertThat(failTurn3.getTerminal().getStatus()).isEqualTo("DENIED");
        // PROVE it routed to transfer_rejected:
        assertThat(failTurn3.getTerminal().getError()).isEqualTo("Transfer MFA rejected");
    }

    @Test
    void testDirectSubflowWithoutCheckpointsExecutesInSingleTurn() {
        String parentYaml = """
                id: direct_parent
                initialState: run_checks
                states:
                  run_checks:
                    type: SUBFLOW
                    subflow: calculation_subflow
                    on:
                      - outcome: SUCCESS
                        target: finish_direct
                      - default: true
                        target: fail_direct

                  finish_direct:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        calculatedScore: "${context.score}"

                  fail_direct:
                    type: TERMINAL
                    terminal:
                      status: DENIED
                """;

        String childYaml = """
                id: calculation_subflow
                initialState: compute
                states:
                  compute:
                    type: DECISION_FORK
                    on:
                      - default: true
                        target: done
                        contextUpdates:
                          score: "100"

                  done:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                """;

        registry.register(parser.parseYaml(parentYaml));
        registry.register(parser.parseYaml(childYaml));

        ExecutionPlan plan = engine.evaluate("direct_parent", null, new SessionContext(), Event.start());

        assertThat(plan.isTerminal()).isTrue();
        assertThat(plan.getTerminal().getStatus()).isEqualTo("SUCCESS");
        assertThat(plan.getTerminal().getClaims()).containsEntry("calculatedScore", 100L);
    }

    @Test
    void testNestedSubflows_ParentCallsChild_ChildCallsGrandchild() {
        String flowAYaml = """
                id: flow_a
                initialState: call_b
                states:
                  call_b:
                    type: SUBFLOW
                    subflow: flow_b
                    on:
                      - outcome: SUCCESS
                        target: finish_a

                  finish_a:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        trace: "${context.trace}"
                """;

        String flowBYaml = """
                id: flow_b
                initialState: call_c
                states:
                  call_c:
                    type: SUBFLOW
                    subflow: flow_c
                    on:
                      - outcome: SUCCESS
                        target: finish_b
                        contextUpdates:
                          trace: "context.trace + '->B'"

                  finish_b:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                """;

        String flowCYaml = """
                id: flow_c
                initialState: do_c
                states:
                  do_c:
                    type: DECISION_FORK
                    on:
                      - default: true
                        target: finish_c
                        contextUpdates:
                          trace: "'C'"

                  finish_c:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                """;

        registry.register(parser.parseYaml(flowAYaml));
        registry.register(parser.parseYaml(flowBYaml));
        registry.register(parser.parseYaml(flowCYaml));

        ExecutionPlan plan = engine.evaluate("flow_a", null, new SessionContext(), Event.start());

        assertThat(plan.isTerminal()).isTrue();
        assertThat(plan.getTerminal().getStatus()).isEqualTo("SUCCESS");
        assertThat(plan.getTerminal().getClaims()).containsEntry("trace", "C->B");
    }

    @Test
    void testIncludesMergeStatesIntoSingleFlow() {
        String parentYaml = """
                id: main_flow_with_include
                initialState: check_allowed
                includes:
                  - "/flows/common_error_states.yaml"
                states:
                  check_allowed:
                    type: DECISION_FORK
                    on:
                      - if: "!context.allowed"
                        target: access_denied_screen
                      - default: true
                        target: grant_access

                  grant_access:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                """;

        FlowDefinition flow = parser.parseYaml(parentYaml);
        registry.register(flow);

        ExecutionPlan plan = engine.evaluate(
                flow,
                null,
                new SessionContext(Map.of("allowed", false)),
                Event.start()
        );

        assertThat(plan.isTerminal()).isTrue();
        assertThat(plan.getTerminal().getStatus()).isEqualTo("DENIED");
        assertThat(plan.getTerminal().getError()).isEqualTo("Access is strictly denied by common policy");
    }
}
