package io.pathfinder.engine.parser;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.model.StateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowParserTest {
    private FlowParser parser;

    @BeforeEach
    void setUp() {
        parser = new FlowParser();
    }

    @Test
    void testParseValidYamlFile() {
        InputStream is = getClass().getResourceAsStream("/flows/oauth_stepup_auth.yaml");
        assertThat(is).isNotNull();

        FlowDefinition flow = parser.parseYaml(is);

        assertThat(flow.getId()).isEqualTo("oauth-stepup-auth");
        assertThat(flow.getVersion()).isEqualTo("1.0.0");
        assertThat(flow.getInitialState()).isEqualTo("evaluate_auth");
        assertThat(flow.getStates()).containsKey("evaluate_auth");
        assertThat(flow.getStates()).containsKey("trigger_stepup_mfa");
        assertThat(flow.getStates()).containsKey("issue_stepup_token");

        // Verify composite state
        var mfaState = flow.getState("trigger_stepup_mfa");
        assertThat(mfaState.getType()).isEqualTo(StateType.COMPOSITE);
        assertThat(mfaState.getBackendCommands()).hasSize(1);
        assertThat(mfaState.getBackendCommands().get(0).getService()).isEqualTo("notification-service");
        assertThat(mfaState.getFrontendSchemas()).hasSize(1);
        assertThat(mfaState.getFrontendSchemas().get(0).getScreenId()).isEqualTo("otp_entry_screen");
    }

    @Test
    void testParseInvalidMissingTargetThrows() {
        String yaml = """
                id: broken-flow
                initialState: start
                states:
                  start:
                    type: BACKEND
                    on:
                      - target: non_existent_state
                """;

        assertThatThrownBy(() -> parser.parseYaml(yaml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transition target 'non_existent_state' from state 'start' does not exist");
    }

    @Test
    void testParseMissingInitialStateThrows() {
        String yaml = """
                id: broken-flow
                states:
                  start:
                    type: TERMINAL
                """;

        assertThatThrownBy(() -> parser.parseYaml(yaml))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
