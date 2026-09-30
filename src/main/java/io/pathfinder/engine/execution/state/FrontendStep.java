package io.pathfinder.engine.execution.state;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FrontendStep {
    private final String screenId;
    private final String title;
    private final String description;
    private final JsonNode jsonSchema;
    private final JsonNode uiSchema;
    private final Map<String, Object> data;
    private final List<String> validationErrors;

    public FrontendStep(
            String screenId,
            String title,
            String description,
            JsonNode jsonSchema,
            JsonNode uiSchema,
            List<String> validationErrors) {
        this(screenId, title, description, jsonSchema, uiSchema, Collections.emptyMap(), validationErrors);
    }

    @JsonCreator
    public FrontendStep(
            @JsonProperty("screenId") String screenId,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("jsonSchema") JsonNode jsonSchema,
            @JsonProperty("uiSchema") JsonNode uiSchema,
            @JsonProperty("data") @JsonAlias({"props", "parameters", "initialData", "initial_data"}) Map<String, Object> data,
            @JsonProperty("validationErrors") List<String> validationErrors) {
        this.screenId = screenId;
        this.title = title;
        this.description = description;
        this.jsonSchema = jsonSchema;
        this.uiSchema = uiSchema;
        this.data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
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

    public Map<String, Object> getData() {
        return data;
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }

    public boolean hasErrors() {
        return !validationErrors.isEmpty();
    }

    @Override
    public String toString() {
        return "FrontendStep{" +
                "screenId='" + screenId + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", data=" + data +
                ", validationErrors=" + validationErrors +
                '}';
    }
}
