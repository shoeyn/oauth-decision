package io.pathfinder.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.pathfinder.engine.execution.eval.CelExpressionEvaluator;
import io.pathfinder.engine.flow.parser.FlowParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityAndRobustnessTest {
    private final FlowParser parser = new FlowParser();
    private final CelExpressionEvaluator cel = new CelExpressionEvaluator();

    @Test
    void testSubflowAndOutcomeVariablesAccessibleInCelExpressions() {
        Map<String, Object> bindings = Map.of(
                "outcome", "SUCCESS",
                "subflow", Map.of("status", "SUCCESS", "riskScore", 15L)
        );

        boolean matchOutcome = cel.evaluateCondition("outcome == 'SUCCESS'", bindings);
        boolean matchStatus = cel.evaluateCondition("subflow.status == 'SUCCESS'", bindings);
        boolean matchRisk = cel.evaluateCondition("subflow.riskScore < 20", bindings);

        assertThat(matchOutcome).isTrue();
        assertThat(matchStatus).isTrue();
        assertThat(matchRisk).isTrue();
    }

    @Test
    void testTemplateResolutionRecursivelyInterpolatesLists() {
        Map<String, Object> bindings = Map.of(
                "context", Map.of("role", "SUPERADMIN", "method", "FIDO2")
        );

        List<Object> rawList = List.of("STATIC_SCOPE", "${context.role}", "${context.method}");
        Object resolved = cel.resolveTemplateValue(rawList, bindings);

        assertThat(resolved).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) resolved;
        assertThat(list).containsExactly("STATIC_SCOPE", "SUPERADMIN", "FIDO2");
    }

    @Test
    void testCelEvaluationFailureGracefullyEvaluatesToFalseInsteadOfCrashing() {
        // Condition references non-existent nested property
        boolean matches = cel.evaluateCondition("results.non_existent.field == true", Map.of());
        assertThat(matches).isFalse();
    }

    @Test
    void testPathTraversalSequenceInIncludeIsRejected() {
        String traversalYaml = """
                id: exploit_flow
                initialState: s1
                includes:
                  - "../../etc/passwd"
                states:
                  s1:
                    type: TERMINAL
                """;

        SecurityException ex = assertThrows(SecurityException.class, () -> parser.parseYaml(traversalYaml));
        assertThat(ex.getMessage()).contains("Path traversal sequence forbidden");
    }

    @Test
    void testNonTerminalDeadEndStateFailsValidation() {
        String deadEndYaml = """
                id: dead_end_flow
                initialState: black_hole
                states:
                  black_hole:
                    type: DECISION_FORK
                """;

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> parser.parseYaml(deadEndYaml));
        assertThat(ex.getMessage()).contains("Non-terminal state 'black_hole' has no transitions, commands, or schemas");
    }
}
