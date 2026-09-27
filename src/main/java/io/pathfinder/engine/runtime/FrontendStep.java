package io.pathfinder.engine.runtime;

import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

public class FrontendStep {
    private final String screenId;
    private final String title;
    private final String description;
    private final JsonNode jsonSchema;
    private final JsonNode uiSchema;
    private final List<String> validationErrors;

    public FrontendStep(
            String screenId,
            String title,
            String description,
            JsonNode jsonSchema,
            JsonNode uiSchema,
            List<String> validationErrors) {
        this.screenId = screenId;
        this.title = title;
        this.description = description;
        this.jsonSchema = jsonSchema;
        this.uiSchema = uiSchema;
        this.validationErrors = validationErrors != null ? Collections.unmodifiableList(validationErrors) : Collections.emptyList();
    }

    public String getScreenId() {
        return screenId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public JsonNode getJsonSchema() {
        return jsonSchema;
    }

    public JsonNode getUiSchema() {
        return uiSchema;
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }

    public boolean hasErrors() {
        return !validationErrors.isEmpty();
    }
}
