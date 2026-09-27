package io.pathfinder.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

public class FrontendSchemaDefinition {
    private final String screenId;
    private final String title;
    private final String description;
    private final JsonNode jsonSchema;
    private final JsonNode uiSchema;

    @JsonCreator
    public FrontendSchemaDefinition(
            @JsonProperty("screenId") String screenId,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("jsonSchema") JsonNode jsonSchema,
            @JsonProperty("uiSchema") JsonNode uiSchema) {
        this.screenId = screenId;
        this.title = title;
        this.description = description;
        this.jsonSchema = jsonSchema;
        this.uiSchema = uiSchema;
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
}
