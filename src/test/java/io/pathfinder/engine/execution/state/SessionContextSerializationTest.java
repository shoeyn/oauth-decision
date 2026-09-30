package io.pathfinder.engine.execution.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SessionContextSerializationTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void testSessionContextJacksonRoundtrip() throws Exception {
        SessionContext original = new SessionContext(
                Map.of("userId", "alice", "role", "admin"),
                Map.of("tempKey", "tempValue"),
                List.of("state1", "state2"),
                "flow_1",
                List.of(new StackFrame("parent_flow", "return_state")),
                Set.of("customSecret")
        );

        String json = mapper.writeValueAsString(original);
        assertThat(json).isNotEmpty();

        SessionContext deserialized = mapper.readValue(json, SessionContext.class);

        assertThat(deserialized.getData()).containsEntry("userId", "alice").containsEntry("role", "admin");
        assertThat(deserialized.getTransientData()).containsEntry("tempKey", "tempValue");
        assertThat(deserialized.getHistory()).containsExactly("state1", "state2");
        assertThat(deserialized.getCurrentFlowId()).isEqualTo("flow_1");
        assertThat(deserialized.hasCallStack()).isTrue();
        assertThat(deserialized.peekFrame().getFlowId()).isEqualTo("parent_flow");
        assertThat(deserialized.peekFrame().getReturnStateId()).isEqualTo("return_state");
        assertThat(deserialized.isSensitive("customSecret")).isTrue();
    }

    @Test
    void testCheckpointJacksonRoundtrip() throws Exception {
        Checkpoint original = new Checkpoint("subflow_mfa", "prompt_otp", List.of("otpCode"));
        String json = mapper.writeValueAsString(original);

        Checkpoint deserialized = mapper.readValue(json, Checkpoint.class);

        assertThat(deserialized.getFlowId()).isEqualTo("subflow_mfa");
        assertThat(deserialized.getResumeState()).isEqualTo("prompt_otp");
        assertThat(deserialized.getExpectedInputs()).containsExactly("otpCode");
        assertThat(deserialized.getAction()).isEqualTo(Checkpoint.ACTION_COME_BACK);
    }

    @Test
    void testExecutionPlanJacksonRoundtrip() throws Exception {
        SessionContext context = new SessionContext(Map.of("userId", "user_123"));
        Checkpoint checkpoint = new Checkpoint("flow_1", "state_1", List.of("input"));
        BackendStep backend = new BackendStep("cmd1", "srv1", Map.of("k", "v"));

        ExecutionPlan original = ExecutionPlan.builder()
                .currentState("state_1")
                .addBackendStep(backend)
                .checkpoint(checkpoint)
                .updatedContext(context)
                .build();

        String json = mapper.writeValueAsString(original);
        ExecutionPlan deserialized = mapper.readValue(json, ExecutionPlan.class);

        assertThat(deserialized.getCurrentState()).isEqualTo("state_1");
        assertThat(deserialized.hasBackendSteps()).isTrue();
        assertThat(deserialized.getBackendSteps().get(0).getStepId()).isEqualTo("cmd1");
        assertThat(deserialized.getCheckpoint().getResumeState()).isEqualTo("state_1");
        assertThat(deserialized.getUpdatedContext().getData()).containsEntry("userId", "user_123");
    }

    @Test
    void testSensitiveDataAutoRedactionAndSafeLogging() {
        SessionContext context = new SessionContext(Map.of(
                "userId", "alice",
                "password", "SuperSecret123!",
                "otpCode", "987654",
                "normalField", "regularValue"
        ));

        // 1. Raw data still retains values for backend services that need them
        assertThat(context.getData().get("password")).isEqualTo("SuperSecret123!");
        assertThat(context.getData().get("otpCode")).isEqualTo("987654");

        // 2. Safe map automatically redacts default sensitive keys
        Map<String, Object> safeMap = context.toSafeMap();
        assertThat(safeMap.get("password")).isEqualTo("[REDACTED]");
        assertThat(safeMap.get("otpCode")).isEqualTo("[REDACTED]");
        assertThat(safeMap.get("userId")).isEqualTo("alice");
        assertThat(safeMap.get("normalField")).isEqualTo("regularValue");

        // 3. toString() (used by loggers) never reveals secrets
        String logOutput = context.toString();
        assertThat(logOutput).doesNotContain("SuperSecret123!");
        assertThat(logOutput).doesNotContain("987654");
        assertThat(logOutput).contains("[REDACTED]");
        assertThat(logOutput).contains("userId=alice");
    }

    @Test
    void testExplicitDataScrubbing() {
        SessionContext context = new SessionContext(Map.of(
                "userId", "alice",
                "password", "CleartextPassword!",
                "otpCode", "123456"
        ));

        // When authentication passes, host or flow can scrub credentials completely:
        SessionContext scrubbed = context.without("password", "otpCode");

        assertThat(scrubbed.getData()).doesNotContainKey("password");
        assertThat(scrubbed.getData()).doesNotContainKey("otpCode");
        assertThat(scrubbed.getData()).containsEntry("userId", "alice");
    }

    @Test
    void testSessionContextBuilder() {
        SessionContext context = SessionContext.builder()
                .data("userId", "e8d47b6a-9b12-4c22-8399-5ef86134b220")
                .data("email", "alice_smith@example.com")
                .config("clientId", "demo-client")
                .config("requireMfa", true)
                .input(Map.of("otpCode", "123456"))
                .build();

        assertThat(context.getData()).containsEntry("userId", "e8d47b6a-9b12-4c22-8399-5ef86134b220");
        assertThat(context.getConfig()).containsEntry("clientId", "demo-client");
        assertThat(context.getInput()).containsEntry("otpCode", "123456");
    }

    @Test
    void testSessionContextDeserializationWithExtraPropertiesAndAliases() throws Exception {
        // Simulating a Redis JSON payload that has extra metadata (e.g. from Rails or proxy)
        String redisJson = """
            {
              "context": {
                "username": "alice",
                "email": "alice@example.com"
              },
              "config": {
                "clientId": "portal"
              },
              "txId": "c4b4f572-8888-4444-9999-123456789abc",
              "created_at": "2026-09-29T20:30:00Z",
              "extraProxyHeader": "x-forwarded-for"
            }
            """;

        SessionContext context = mapper.readValue(redisJson, SessionContext.class);

        assertThat(context.getData()).containsEntry("username", "alice");
        assertThat(context.getData()).containsEntry("email", "alice@example.com");
        assertThat(context.getConfig()).containsEntry("clientId", "portal");
    }

    @Test
    void testFrontendStepSerializationWithData() throws Exception {
        FrontendStep step = new FrontendStep(
                "otp_screen",
                "Verify Alice",
                "Enter code sent to alice@example.com",
                null,
                null,
                Map.of("maskedPhone", "***-***-1234", "clientName", "Demo Client"),
                List.of()
        );

        String json = mapper.writeValueAsString(step);
        FrontendStep deserialized = mapper.readValue(json, FrontendStep.class);

        assertThat(deserialized.getScreenId()).isEqualTo("otp_screen");
        assertThat(deserialized.getTitle()).isEqualTo("Verify Alice");
        assertThat(deserialized.getData()).containsEntry("maskedPhone", "***-***-1234");
        assertThat(deserialized.getData()).containsEntry("clientName", "Demo Client");
    }
}
