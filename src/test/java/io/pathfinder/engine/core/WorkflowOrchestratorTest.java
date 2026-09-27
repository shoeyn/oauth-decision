package io.pathfinder.engine.core;

import io.pathfinder.engine.command.CommandRegistry;
import io.pathfinder.engine.command.InMemoryCommandRegistry;
import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.parser.FlowParser;
import io.pathfinder.engine.runtime.Event;
import io.pathfinder.engine.runtime.ExecutionPlan;
import io.pathfinder.engine.runtime.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowOrchestratorTest {
    private final FlowParser parser = new FlowParser();
    private DecisionEngine decisionEngine;
    private CommandRegistry commandRegistry;
    private WorkflowOrchestrator orchestrator;

    private final String orchestratedFlowYaml = """
            id: orchestrated-auth-flow
            initialState: evaluate_risk
            states:
              evaluate_risk:
                type: BACKEND
                commands:
                  - id: check_fraud
                    service: fraud-engine
                    payload:
                      user: "${context.userId}"
                on:
                  - if: "results.check_fraud.score < 50"
                    target: approve
                  - default: true
                    target: challenge_mfa
              challenge_mfa:
                type: FRONTEND
                schema:
                  screenId: otp_screen
                  jsonSchema:
                    type: object
                    required:
                      - otpCode
                    properties:
                      otpCode:
                        type: string
                on:
                  - event: SUBMIT
                    target: verify_mfa
              verify_mfa:
                type: BACKEND
                commands:
                  - id: verify_otp
                    service: otp-service
                    payload:
                      code: "${context.otpCode}"
                on:
                  - if: "results.verify_otp.valid == true"
                    target: approve_stepup
                  - default: true
                    target: reject
              approve:
                type: TERMINAL
                terminal:
                  status: SUCCESS
                  claims:
                    acr: "urn:pathfinder:auth:level1"
                    amr: ["pwd"]
              approve_stepup:
                type: TERMINAL
                terminal:
                  status: SUCCESS
                  claims:
                    acr: "urn:pathfinder:auth:level2"
                    amr: ["pwd", "otp"]
              reject:
                type: TERMINAL
                terminal:
                  status: DENIED
                  error: "Authentication failed"
            """;

    @BeforeEach
    void setUp() {
        decisionEngine = new DecisionEngine();
        commandRegistry = new InMemoryCommandRegistry();
        orchestrator = new WorkflowOrchestrator(decisionEngine, commandRegistry);
    }

    @Test
    void testLowRiskPathExecutesBackendCommandsAndReachesTerminalSuccessInSingleInvocation() {
        FlowDefinition flow = parser.parseYaml(orchestratedFlowYaml);
        decisionEngine.getFlowRegistry().register(flow);

        // Register fraud-engine handler returning low risk score
        commandRegistry.register("fraud-engine", payload -> {
            assertThat(payload.get("user")).isEqualTo("user_low_risk");
            return Map.of("score", 15);
        });

        SessionContext context = new SessionContext(Map.of("userId", "user_low_risk"));

        // Single call to orchestrator.run() executes commands, evaluates CEL condition, and reaches TERMINAL
        ExecutionPlan plan = orchestrator.run("orchestrated-auth-flow", null, context, Event.start());

        assertThat(plan.isTerminal()).isTrue();
        assertThat(plan.getCurrentState()).isEqualTo("approve");
        assertThat(plan.getTerminalResult().getStatus()).isEqualTo("SUCCESS");
        assertThat(plan.getTerminalResult().getClaims()).containsEntry("acr", "urn:pathfinder:auth:level1");
    }

    @Test
    void testHighRiskPathExecutesRiskEvaluationAndHaltsAtMfaFrontendScreen() {
        FlowDefinition flow = parser.parseYaml(orchestratedFlowYaml);
        decisionEngine.getFlowRegistry().register(flow);

        // Register fraud-engine handler returning high risk score
        commandRegistry.register("fraud-engine", payload -> Map.of("score", 85));

        // Register otp-service handler
        commandRegistry.register("otp-service", payload -> {
            boolean valid = "123456".equals(payload.get("code"));
            return Map.of("valid", valid);
        });

        SessionContext context = new SessionContext(Map.of("userId", "user_high_risk"));

        // Stage 1: Runs fraud check, transitions to challenge_mfa, and stops at FRONTEND checkpoint
        ExecutionPlan stage1 = orchestrator.run("orchestrated-auth-flow", null, context, Event.start());

        assertThat(stage1.isTerminal()).isFalse();
        assertThat(stage1.hasFrontendSteps()).isTrue();
        assertThat(stage1.getCurrentState()).isEqualTo("challenge_mfa");
        assertThat(stage1.getFrontendSteps().get(0).getScreenId()).isEqualTo("otp_screen");

        // Stage 2: User enters correct OTP -> resumes orchestrator -> executes otp-service -> finishes with stepup token
        ExecutionPlan stage2 = orchestrator.run(
                "orchestrated-auth-flow",
                stage1.getCurrentState(),
                stage1.getUpdatedContext(),
                Event.submit(Map.of("otpCode", "123456"))
        );

        assertThat(stage2.isTerminal()).isTrue();
        assertThat(stage2.getCurrentState()).isEqualTo("approve_stepup");
        assertThat(stage2.getTerminalResult().getStatus()).isEqualTo("SUCCESS");
        assertThat(stage2.getTerminalResult().getClaims()).containsEntry("acr", "urn:pathfinder:auth:level2");
    }

    @Test
    void testBackendCommandExceptionFailsClosedWithErrorTerminalWhenNoOnErrorDefined() {
        FlowDefinition flow = parser.parseYaml(orchestratedFlowYaml);
        decisionEngine.getFlowRegistry().register(flow);

        commandRegistry.register("fraud-engine", payload -> {
            throw new RuntimeException("Fraud service connection timeout");
        });

        ExecutionPlan plan = orchestrator.run("orchestrated-auth-flow", null, new SessionContext(Map.of("userId", "user_123")), Event.start());

        assertThat(plan.isTerminal()).isTrue();
        assertThat(plan.getTerminalResult().getStatus()).isEqualTo("ERROR");
        assertThat(plan.getTerminalResult().getError()).contains("Fraud service connection timeout");
        assertThat(plan.getUpdatedContext().getData().get("lastError")).isEqualTo("Fraud service connection timeout");
    }

    @Test
    void testBackendCommandExceptionTransitionsToOnErrorStateWhenConfigured() {
        String flowWithOnError = """
                id: fault-tolerant-flow
                initialState: query_service
                states:
                  query_service:
                    type: BACKEND
                    onError: fallback_state
                    commands:
                      - id: call_remote
                        service: faulty-service
                        payload: {}
                    on:
                      - if: "results.call_remote.status == 'OK'"
                        target: success_state
                      - default: true
                        target: fallback_state
                  success_state:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                  fallback_state:
                    type: TERMINAL
                    terminal:
                      status: DEGRADED
                      error: "Fell back due to service error"
                """;

        FlowDefinition flow = parser.parseYaml(flowWithOnError);
        decisionEngine.getFlowRegistry().register(flow);

        commandRegistry.register("faulty-service", payload -> {
            throw new IllegalStateException("Service 503 unavailable");
        });

        ExecutionPlan plan = orchestrator.run("fault-tolerant-flow", null, new SessionContext(), Event.start());

        assertThat(plan.isTerminal()).isTrue();
        assertThat(plan.getCurrentState()).isEqualTo("fallback_state");
        assertThat(plan.getTerminalResult().getStatus()).isEqualTo("DEGRADED");
        assertThat(plan.getUpdatedContext().getData().get("lastError")).isEqualTo("Service 503 unavailable");
    }
}
