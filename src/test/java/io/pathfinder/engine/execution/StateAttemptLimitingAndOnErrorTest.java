package io.pathfinder.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.pathfinder.engine.execution.state.Event;
import io.pathfinder.engine.execution.state.ExecutionPlan;
import io.pathfinder.engine.execution.state.SessionContext;
import io.pathfinder.engine.flow.model.FlowDefinition;
import io.pathfinder.engine.flow.parser.FlowParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateAttemptLimitingAndOnErrorTest {
    private final FlowParser parser = new FlowParser();
    private final DecisionEngine engine = new DecisionEngine();

    private final String retryFlowYaml = """
            id: retry-challenge-flow
            initialState: challenge_state
            states:
              challenge_state:
                type: FRONTEND
                maxAttempts: 3
                onError: lockout_state
                schema:
                  screenId: pin_screen
                  jsonSchema:
                    type: object
                    required:
                      - pin
                    properties:
                      pin:
                        type: string
                on:
                  - if: "input.pin == '1234'"
                    target: success_state
                  - default: true
                    target: challenge_state
              success_state:
                type: TERMINAL
                terminal:
                  status: SUCCESS
              lockout_state:
                type: TERMINAL
                terminal:
                  status: LOCKED
                  error: "Account temporarily locked due to repeated incorrect attempts"
            """;

    @Test
    void testStateAttemptLimitingTriggersOnErrorStateWhenLimitExceeded() {
        FlowDefinition flow = parser.parseYaml(retryFlowYaml);
        SessionContext context = new SessionContext();

        // Turn 1: yield initial challenge
        ExecutionPlan turn1 = engine.evaluate(flow, null, context, Event.start());
        assertThat(turn1.hasFrontendSteps()).isTrue();
        assertThat(turn1.getCurrentState()).isEqualTo("challenge_state");

        // Attempt 1: wrong PIN
        ExecutionPlan turn2 = engine.evaluate(flow, turn1.getCurrentState(), turn1.getUpdatedContext(), Event.submit(Map.of("pin", "0000")));
        assertThat(turn2.getCurrentState()).isEqualTo("challenge_state");
        assertThat(turn2.getUpdatedContext().getAttemptCount("challenge_state")).isEqualTo(1);

        // Attempt 2: wrong PIN
        ExecutionPlan turn3 = engine.evaluate(flow, turn2.getCurrentState(), turn2.getUpdatedContext(), Event.submit(Map.of("pin", "1111")));
        assertThat(turn3.getCurrentState()).isEqualTo("challenge_state");
        assertThat(turn3.getUpdatedContext().getAttemptCount("challenge_state")).isEqualTo(2);

        // Attempt 3: wrong PIN (hits maxAttempts=3)
        ExecutionPlan turn4 = engine.evaluate(flow, turn3.getCurrentState(), turn3.getUpdatedContext(), Event.submit(Map.of("pin", "2222")));
        assertThat(turn4.getCurrentState()).isEqualTo("challenge_state");
        assertThat(turn4.getUpdatedContext().getAttemptCount("challenge_state")).isEqualTo(3);

        // Attempt 4: exceeds maxAttempts (attempt 4 > 3) -> transitions to onError: lockout_state
        ExecutionPlan turn5 = engine.evaluate(flow, turn4.getCurrentState(), turn4.getUpdatedContext(), Event.submit(Map.of("pin", "3333")));
        assertThat(turn5.isTerminal()).isTrue();
        assertThat(turn5.getCurrentState()).isEqualTo("lockout_state");
        assertThat(turn5.getTerminalResult().getStatus()).isEqualTo("LOCKED");
        assertThat(turn5.getTerminalResult().getError()).contains("locked due to repeated incorrect attempts");
    }

    @Test
    void testMaxAttemptsWithoutOnErrorYieldsTerminalDenied() {
        String flowNoOnError = """
                id: strict-flow
                initialState: prompt
                states:
                  prompt:
                    type: FRONTEND
                    maxAttempts: 1
                    schema:
                      screenId: s1
                      jsonSchema:
                        type: object
                        properties:
                          code:
                            type: string
                    on:
                      - if: "input.code == 'valid'"
                        target: done
                      - default: true
                        target: prompt
                  done:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                """;

        FlowDefinition flow = parser.parseYaml(flowNoOnError);
        ExecutionPlan turn1 = engine.evaluate(flow, null, new SessionContext(), Event.start());

        // Attempt 1: bad input
        ExecutionPlan turn2 = engine.evaluate(flow, turn1.getCurrentState(), turn1.getUpdatedContext(), Event.submit(Map.of("code", "bad")));
        assertThat(turn2.getCurrentState()).isEqualTo("prompt");

        // Attempt 2: exceeds maxAttempts=1 -> Terminal DENIED
        ExecutionPlan turn3 = engine.evaluate(flow, turn2.getCurrentState(), turn2.getUpdatedContext(), Event.submit(Map.of("code", "bad2")));
        assertThat(turn3.isTerminal()).isTrue();
        assertThat(turn3.getTerminalResult().getStatus()).isEqualTo("DENIED");
        assertThat(turn3.getTerminalResult().getError()).contains("Maximum attempts exceeded");
    }
}
