package io.pathfinder.engine.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates strict parser validation and descriptive error messaging for malformed flow graphs.
 */
class FlowParserValidationEdgeCasesTest {
    private FlowParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new FlowParser();
    }

    @Test
    void testSubflowStateWithoutSubflowIdentifierThrowsDescriptiveError() {
        String yaml = """
                id: invalid_subflow
                initialState: call_step
                states:
                  call_step:
                    type: SUBFLOW
                    on:
                      - default: true
                        target: call_step
                """;

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> parser.parseYaml(yaml));
        assertThat(ex.getMessage()).contains("State 'call_step' of type SUBFLOW must specify a 'subflow' identifier");
    }

    @Test
    void testInitialStateMissingFromStatesMapThrowsDescriptiveError() {
        String yaml = """
                id: bad_initial_state
                initialState: state_foo
                states:
                  state_bar:
                    type: TERMINAL
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parseYaml(yaml));
        assertThat(ex.getMessage()).contains("Initial state 'state_foo' not found in states map");
    }

    @Test
    void testMissingFlowIdThrowsDescriptiveError() {
        String yaml = """
                initialState: start
                states:
                  start:
                    type: TERMINAL
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parseYaml(yaml));
        assertThat(ex.getMessage()).contains("id must not be null");
    }

    @Test
    void testTransitionWithNullOrEmptyTargetThrowsDescriptiveError() {
        String yaml = """
                id: empty_target_flow
                initialState: step1
                states:
                  step1:
                    type: DECISION_FORK
                    on:
                      - target: ""
                """;

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> parser.parseYaml(yaml));
        assertThat(ex.getMessage()).contains("Transition from state 'step1' has null or empty target");
    }

    @Test
    void testUnresolvableIncludeThrowsDescriptiveError() {
        String yaml = """
                id: bad_include_flow
                initialState: step1
                includes:
                  - "/non_existent_folder/missing_file.yaml"
                states:
                  step1:
                    type: TERMINAL
                """;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parseYaml(yaml));
        assertThat(ex.getMessage()).contains("Unable to resolve flow include: /non_existent_folder/missing_file.yaml");
    }
}
