package io.memoryos.ai;

import java.util.ArrayList;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON schema of a typed answer in the form a provider enforces. Spring AI derives the schema from the type and
 * leaves a nullable field out of {@code required}; OpenAI's strict mode wants every property required, with an
 * optional one typed as nullable instead, so that is how an optional field is written here.
 */
final class OutputSchemas {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OutputSchemas() {}

    static String strict(Class<?> type) {
        JsonNode schema = JSON.readTree(new BeanOutputConverter<>(type).getJsonSchema());
        close(schema);
        return JSON.writeValueAsString(schema);
    }

    private static void close(JsonNode node) {
        if (node instanceof ObjectNode object && object.path("properties") instanceof ObjectNode properties) {
            var required = new ArrayList<String>();
            object.path("required").forEach(name -> required.add(name.asString()));
            ArrayNode all = JSON.createArrayNode();
            for (var property : properties.properties()) {
                all.add(property.getKey());
                if (!required.contains(property.getKey()) && property.getValue() instanceof ObjectNode optional
                        && optional.path("type").isString()) {
                    optional.set("type", JSON.createArrayNode().add(optional.path("type").asString()).add("null"));
                }
                close(property.getValue());
            }
            object.set("required", all);
            object.put("additionalProperties", false);
        }
        if (node.has("items")) close(node.path("items"));
    }
}
