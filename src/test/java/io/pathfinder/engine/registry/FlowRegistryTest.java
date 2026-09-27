package io.pathfinder.engine.registry;

import io.pathfinder.engine.model.FlowDefinition;
import io.pathfinder.engine.parser.FlowParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FlowRegistryTest {

    @Test
    void testInMemoryFlowRegistry() {
        FlowParser parser = new FlowParser();
        FlowDefinition flow1 = parser.parseYaml("""
                id: flow_1
                initialState: s1
                states:
                  s1:
                    type: TERMINAL
                """);
        FlowDefinition flow2 = parser.parseYaml("""
                id: flow_2
                initialState: s2
                states:
                  s2:
                    type: TERMINAL
                """);

        InMemoryFlowRegistry registry = new InMemoryFlowRegistry(flow1);
        registry.register(flow2);

        assertThat(registry.hasFlow("flow_1")).isTrue();
        assertThat(registry.hasFlow("flow_2")).isTrue();
        assertThat(registry.hasFlow("unknown")).isFalse();
        assertThat(registry.getFlow("flow_1")).contains(flow1);
        assertThat(registry.getAllFlows()).containsExactlyInAnyOrder(flow1, flow2);
    }

    @Test
    void testClasspathFlowRegistry() {
        ClasspathFlowRegistry registry = new ClasspathFlowRegistry();
        FlowDefinition flow = registry.registerResource("/flows/oauth_stepup_auth.yaml");

        assertThat(flow.getId()).isEqualTo("oauth-stepup-auth");
        assertThat(registry.hasFlow("oauth-stepup-auth")).isTrue();
        assertThat(registry.getFlow("oauth-stepup-auth")).isPresent();
    }

    @Test
    void testFileSystemFlowRegistry(@TempDir Path tempDir) throws IOException {
        String yamlContent = """
                id: disk_flow
                initialState: init
                states:
                  init:
                    type: TERMINAL
                """;
        Path filePath = tempDir.resolve("disk_flow.yaml");
        Files.writeString(filePath, yamlContent);

        FileSystemFlowRegistry registry = new FileSystemFlowRegistry();
        registry.registerDirectory(tempDir.toFile());

        assertThat(registry.hasFlow("disk_flow")).isTrue();
        assertThat(registry.getFlow("disk_flow").get().getId()).isEqualTo("disk_flow");
    }
}
