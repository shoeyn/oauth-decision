package io.pathfinder.engine.persistence;

import io.pathfinder.engine.runtime.Checkpoint;
import io.pathfinder.engine.runtime.SessionContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FlowStatePersistenceTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void testFlowStateJacksonRoundtripSerialization() throws Exception {
        SessionContext context = new SessionContext(Map.of("userId", "bob", "riskScore", 12))
                .withInput(Map.of("otpCode", "654321"))
                .withIncrementedAttempt("state_otp");

        Checkpoint checkpoint = new Checkpoint("oauth-flow", "state_otp", List.of("input"));

        FlowState original = new FlowState("inst-1234", "oauth-flow", "state_otp", context, checkpoint);

        String json = mapper.writeValueAsString(original);
        assertThat(json).isNotEmpty();

        FlowState deserialized = mapper.readValue(json, FlowState.class);

        assertThat(deserialized.getFlowInstanceId()).isEqualTo("inst-1234");
        assertThat(deserialized.getFlowId()).isEqualTo("oauth-flow");
        assertThat(deserialized.getCurrentStateId()).isEqualTo("state_otp");
        assertThat(deserialized.getContext().getData().get("userId")).isEqualTo("bob");
        assertThat(deserialized.getContext().getInput().get("otpCode")).isEqualTo("654321");
        assertThat(deserialized.getContext().getAttemptCount("state_otp")).isEqualTo(1);
        assertThat(deserialized.getCheckpoint().getResumeState()).isEqualTo("state_otp");
    }

    @Test
    void testInMemoryFlowStateRepositoryCrudAndExpiration() throws Exception {
        InMemoryFlowStateRepository repo = new InMemoryFlowStateRepository();

        SessionContext context = new SessionContext(Map.of("clientId", "client_abc"));
        FlowState state = new FlowState("tx_999", "flow_1", "state_init", context, null);

        // 1. Save and retrieve
        repo.save("tx_999", state, Duration.ofMinutes(5));
        Optional<FlowState> found = repo.find("tx_999");
        assertThat(found).isPresent();
        assertThat(found.get().getFlowInstanceId()).isEqualTo("tx_999");

        // 2. Delete
        repo.delete("tx_999");
        assertThat(repo.find("tx_999")).isEmpty();

        // 3. TTL Expiration
        repo.save("tx_exp", state, Duration.ofMillis(50));
        assertThat(repo.find("tx_exp")).isPresent();

        Thread.sleep(80);
        assertThat(repo.find("tx_exp")).isEmpty();
    }
}
