package io.pathfinder.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.pathfinder.engine.execution.state.Event;
import io.pathfinder.engine.execution.state.ExecutionPlan;
import io.pathfinder.engine.execution.state.SessionContext;
import io.pathfinder.engine.flow.model.FlowDefinition;
import io.pathfinder.engine.flow.parser.FlowParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SafeInputScopingAndTamperingProtectionTest {
    private final FlowParser parser = new FlowParser();
    private final DecisionEngine engine = new DecisionEngine();

    private final String formFlowYaml = """
            id: form-security-flow
            initialState: ask_otp
            states:
              ask_otp:
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
                    target: finish
                    contextUpdates:
                      verifiedCode: "input.otpCode"
              finish:
                type: TERMINAL
                terminal:
                  status: SUCCESS
                  claims:
                    sub: "${context.userId}"
            """;

    @Test
    void testUntrustedFormSubmissionCannotOverwritePreexistingContextKeys() {
        FlowDefinition flow = parser.parseYaml(formFlowYaml);

        // Initial context contains server-verified identity & security attributes
        SessionContext initialContext = new SessionContext(Map.of(
                "userId", "alice_id",
                "roles", List.of("ROLE_USER"),
                "riskScore", 10
        ));

        // Turn 1: yield the frontend screen
        ExecutionPlan turn1 = engine.evaluate(flow, null, initialContext, Event.start());
        assertThat(turn1.hasFrontendSteps()).isTrue();

        // Turn 2: Malicious client submits valid otpCode along with hostile attempts to overwrite userId, roles, and riskScore
        Map<String, Object> maliciousPayload = Map.of(
                "otpCode", "123456",
                "userId", "attacker_admin",
                "roles", List.of("ROLE_ADMIN", "ROLE_SUPERUSER"),
                "riskScore", 0,
                "injectedProperty", "hacked"
        );

        ExecutionPlan turn2 = engine.evaluate(flow, turn1.getCurrentState(), turn1.getUpdatedContext(), Event.submit(maliciousPayload));

        SessionContext updatedContext = turn2.getUpdatedContext();

        // 1. Legitimate declared property was merged
        assertThat(updatedContext.getData().get("otpCode")).isEqualTo("123456");

        // 2. Pre-existing server-set properties were PROTECTED and NOT overwritten
        assertThat(updatedContext.getData().get("userId")).isEqualTo("alice_id");
        assertThat(updatedContext.getData().get("roles")).isEqualTo(List.of("ROLE_USER"));
        assertThat(updatedContext.getData().get("riskScore")).isEqualTo(10);

        // 3. Undeclared arbitrary property was filtered out from root context
        assertThat(updatedContext.getData()).doesNotContainKey("injectedProperty");

        // 4. Input namespace preserves the actual raw submitted values safely
        assertThat(updatedContext.getInput().get("otpCode")).isEqualTo("123456");
        assertThat(updatedContext.getInput().get("userId")).isEqualTo("attacker_admin");

        // 5. Terminal claims reflect the protected userId, not the attacker's attempted overwrite
        assertThat(turn2.isTerminal()).isTrue();
        assertThat(turn2.getTerminalResult().getClaims().get("sub")).isEqualTo("alice_id");
    }
}
