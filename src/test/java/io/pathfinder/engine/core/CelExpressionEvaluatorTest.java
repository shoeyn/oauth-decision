package io.pathfinder.engine.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CelExpressionEvaluatorTest {
    private CelExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new CelExpressionEvaluator();
    }

    @Test
    void testConditionEvaluation() {
        Map<String, Object> bindings = Map.of(
                "context", Map.of("userId", "usr_123", "role", "admin"),
                "results", Map.of("riskScore", 85L),
                "input", Map.of("otp", "123456")
        );

        assertThat(evaluator.evaluateCondition("results.riskScore >= 80", bindings)).isTrue();
        assertThat(evaluator.evaluateCondition("results.riskScore < 50", bindings)).isFalse();
        assertThat(evaluator.evaluateCondition("context.role == 'admin'", bindings)).isTrue();
        assertThat(evaluator.evaluateCondition("input.otp.size() == 6", bindings)).isTrue();
    }

    @Test
    void testTemplateInterpolation() {
        Map<String, Object> bindings = Map.of(
                "context", Map.of("userId", "usr_456", "tenant", "acme")
        );

        Object single = evaluator.resolveTemplateValue("${context.userId}", bindings);
        assertThat(single).isEqualTo("usr_456");

        Object stringConcat = evaluator.resolveTemplateValue("urn:tenant:${context.tenant}:user:${context.userId}", bindings);
        assertThat(stringConcat).isEqualTo("urn:tenant:acme:user:usr_456");

        Map<String, Object> map = Map.of(
                "id", "${context.userId}",
                "staticKey", "staticVal"
        );
        Object resolvedMap = evaluator.resolveTemplateValue(map, bindings);
        assertThat(resolvedMap).isEqualTo(Map.of("id", "usr_456", "staticKey", "staticVal"));
    }
}
