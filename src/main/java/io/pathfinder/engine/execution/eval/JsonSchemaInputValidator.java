package io.pathfinder.engine.execution.eval;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class JsonSchemaInputValidator {
    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;

    public JsonSchemaInputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : JsonMapper.builder().build();
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
    }

    public List<String> validate(JsonNode schemaNode, Object input) {
        if (schemaNode == null || schemaNode.isNull() || schemaNode.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Schema schema = schemaRegistry.getSchema(schemaNode);
            JsonNode inputNode = objectMapper.valueToTree(input != null ? input : Collections.emptyMap());

            List<Error> errors = schema.validate(inputNode);
            if (errors == null || errors.isEmpty()) {
                return Collections.emptyList();
            }

            List<String> errorMessages = new ArrayList<>();
            for (Error err : errors) {
                errorMessages.add(err.getMessage());
            }
            return errorMessages;
        } catch (Exception e) {
            return List.of("Schema validation error: " + e.getMessage());
        }
    }
}
