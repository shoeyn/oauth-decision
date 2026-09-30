package io.pathfinder.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.pathfinder.engine.execution.state.Event;
import io.pathfinder.engine.execution.state.ExecutionPlan;
import io.pathfinder.engine.execution.state.SessionContext;
import io.pathfinder.engine.flow.model.FlowDefinition;
import io.pathfinder.engine.flow.parser.FlowParser;
import org.junit.jupiter.api.Test;

class CycleDetectionAndInfiniteLoopSafetyTest {

    @Test
    void testCyclicGraphHaltsSafelyWithCycleDetectedCheckpoint() {
        // A graph with state_a -> state_b -> state_a without any human checkpoints
        String cyclicYaml = """
                id: cyclic_loop_flow
                initialState: state_a
                states:
                  state_a:
                    type: DECISION_FORK
                    on:
                      - default: true
                        target: state_b

                  state_b:
                    type: DECISION_FORK
                    on:
                      - default: true
                        target: state_a
                """;

        FlowParser parser = new FlowParser();
        FlowDefinition flow = parser.parseYaml(cyclicYaml);
        DecisionEngine engine = new DecisionEngine();

        // Must terminate safely and not loop indefinitely
        ExecutionPlan plan = engine.evaluate(flow, null, new SessionContext(), Event.start());

        assertThat(plan.isTerminal()).isFalse();
        assertThat(plan.hasCheckpoint()).isTrue();
        assertThat(plan.getCheckpoint().getExpectedInputs()).contains("cycle_detected");
        assertThat(plan.getCheckpoint().getResumeState()).isEqualTo("state_a");
        assertThat(plan.getUpdatedContext().getHistory()).containsExactly("state_a", "state_b", "state_a");
    }
}
