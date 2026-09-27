package io.pathfinder.engine.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaInputValidatorTest {
    private JsonSchemaInputValidator validator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().build();
        validator = new JsonSchemaInputValidator(mapper);
    }

    @Test
    void testValidInputMatchesSchema() throws Exception {
        String schemaJson = """
                {
                  "type": "object",
                  "required": ["otpCode"],
                  "properties": {
                    "otpCode": {
                      "type": "string",
                      "pattern": "^[0-9]{6}$"
                    }
                  }
                }
                """;
        JsonNode schemaNode = mapper.readTree(schemaJson);

        List<String> errors = validator.validate(schemaNode, Map.of("otpCode", "123456"));
        assertThat(errors).isEmpty();
    }

    @Test
    void testInvalidRegexPatternYieldsErrors() throws Exception {
        String schemaJson = """
                {
                  "type": "object",
                  "required": ["otpCode"],
                  "properties": {
                    "otpCode": {
                      "type": "string",
                      "pattern": "^[0-9]{6}$"
                    }
                  }
                }
                """;
        JsonNode schemaNode = mapper.readTree(schemaJson);

        List<String> errorsAlpha = validator.validate(schemaNode, Map.of("otpCode", "abcdef"));
        assertThat(errorsAlpha).isNotEmpty();

        List<String> errorsShort = validator.validate(schemaNode, Map.of("otpCode", "123"));
        assertThat(errorsShort).isNotEmpty();

        List<String> errorsMissing = validator.validate(schemaNode, Map.of());
        assertThat(errorsMissing).isNotEmpty();
    }
}
