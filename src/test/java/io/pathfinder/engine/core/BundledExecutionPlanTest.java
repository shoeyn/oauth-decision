package io.pathfinder.engine.core;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.parser.FlowParser;
import io.pathfinder.engine.runtime.Event;
import io.pathfinder.engine.runtime.ExecutionPlan;
import io.pathfinder.engine.runtime.SessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BundledExecutionPlanTest {
    private FlowParser parser;
    private DecisionEngine engine;

    @BeforeEach
    void setUp() {
        this.parser = new FlowParser();
        this.engine = new DecisionEngine();
    }

    @Test
    void testMultipleBackendStepsAndFrontendScreensBundledTogether() {
        String yaml = """
                id: multi-step-bundled-flow
                initialState: bundle_stage
                states:
                  bundle_stage:
                    type: COMPOSITE
                    commands:
                      - id: cmd_analytics
                        service: analytics-service
                        payload:
                          event: "AUTH_INITIATED"
                      - id: cmd_geo
                        service: geo-service
                        payload:
                          ip: "${context.ip}"
                    schemas:
                      - screenId: screen_personal_info
                        title: "Personal Information"
                        jsonSchema:
                          type: object
                          required: [fullName]
                          properties:
                            fullName:
                              type: string
                      - screenId: screen_preferences
                        title: "Security Preferences"
                        jsonSchema:
                          type: object
                          required: [channel]
                          properties:
                            channel:
                              type: string
                    on:
                      - event: SUBMIT
                        target: finish
                  finish:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                      claims:
                        done: true
                """;

        FlowDefinition flow = parser.parseYaml(yaml);
        SessionContext context = new SessionContext(Map.of("ip", "10.0.0.1"));

        ExecutionPlan plan = engine.evaluate(flow, null, context, Event.start());

        assertThat(plan.isTerminal()).isFalse();
        // Emitted 2 backend steps
        assertThat(plan.getBackendSteps()).hasSize(2);
        assertThat(plan.getBackendSteps().get(0).getStepId()).isEqualTo("cmd_analytics");
        assertThat(plan.getBackendSteps().get(1).getStepId()).isEqualTo("cmd_geo");
        assertThat(plan.getBackendSteps().get(1).getPayload()).containsEntry("ip", "10.0.0.1");

        // Emitted 2 frontend screens together
        assertThat(plan.getFrontendSteps()).hasSize(2);
        assertThat(plan.getFrontendSteps().get(0).getScreenId()).isEqualTo("screen_personal_info");
        assertThat(plan.getFrontendSteps().get(1).getScreenId()).isEqualTo("screen_preferences");

        // Checkpoint yielded to resume on bundle_stage
        assertThat(plan.hasCheckpoint()).isTrue();
        assertThat(plan.getCheckpoint().getResumeState()).isEqualTo("bundle_stage");
    }

    @Test
    void testAuditTrailBreadcrumbsPreservedAcrossTurns() {
        String yaml = """
                id: breadcrumb-flow
                initialState: step1
                states:
                  step1:
                    type: BACKEND
                    commands:
                      - id: c1
                        service: s1
                        payload: {}
                    on:
                      - default: true
                        target: step2
                  step2:
                    type: FRONTEND
                    schemas:
                      - screenId: s2_screen
                        jsonSchema:
                          type: object
                    on:
                      - event: SUBMIT
                        target: step3
                  step3:
                    type: TERMINAL
                    terminal:
                      status: SUCCESS
                """;

        FlowDefinition flow = parser.parseYaml(yaml);
        SessionContext context = new SessionContext();

        // Turn 1: Starts at step1, unconditionally transitions to step2, yields at step2 (frontend)
        ExecutionPlan turn1 = engine.evaluate(flow, null, context, Event.start());
        assertThat(turn1.getCurrentState()).isEqualTo("step2");
        assertThat(turn1.getUpdatedContext().getHistory()).containsExactly("step1", "step2");

        // Turn 2: Submits step2, moves to step3 (terminal)
        ExecutionPlan turn2 = engine.evaluate(flow, turn1.getCurrentState(), turn1.getUpdatedContext(), Event.submit(Map.of()));
        assertThat(turn2.isTerminal()).isTrue();
        assertThat(turn2.getUpdatedContext().getHistory()).containsExactly("step1", "step2", "step3");
    }
}
