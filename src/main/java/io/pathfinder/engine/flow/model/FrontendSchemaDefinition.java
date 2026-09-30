package io.pathfinder.engine.flow.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;
import tools.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FrontendSchemaDefinition {
    private final String screenId;
    private final String title;
    private final String description;
    private final JsonNode jsonSchema;
    private final JsonNode uiSchema;
    private final Map<String, Object> data;

    public FrontendSchemaDefinition(
            String screenId,
            String title,
            String description,
            JsonNode jsonSchema,
            JsonNode uiSchema) {
        this(screenId, title, description, jsonSchema, uiSchema, Collections.emptyMap());
    }

    @JsonCreator
    public FrontendSchemaDefinition(
            @JsonProperty("screenId") String screenId,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("jsonSchema") JsonNode jsonSchema,
            @JsonProperty("uiSchema") JsonNode uiSchema,
            @JsonProperty("data") @JsonAlias({"props", "parameters", "initialData", "initial_data"}) Map<String, Object> data) {
        this.screenId = screenId;
        this.title = title;
        this.description = description;
        this.jsonSchema = jsonSchema;
        this.uiSchema = uiSchema;
        this.data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
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

    public Map<String, Object> getData() {
        return data;
    }
}
