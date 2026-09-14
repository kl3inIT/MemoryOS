package io.memoryos.chat;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Persisted, read-only assistant-ui component tree. No URLs, actions, HTML or arbitrary props. */
public record ChatArtifact(UUID id, String title, String spec) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_SPEC_BYTES = 16384;

    public ChatArtifact {
        if (id == null || title == null || title.isBlank() || title.length() > 120 || spec == null
                || spec.length() > MAX_SPEC_BYTES || spec.getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES)
            throw new IllegalArgumentException("Invalid artifact");
        var tree = JSON.readTree(spec);
        fields(tree, Set.of("root"));
        validate(tree.path("root"), 0, new int[1]);
        spec = JSON.writeValueAsString(tree);
    }

    private static void validate(JsonNode node, int depth, int[] count) {
        if (depth > 8 || ++count[0] > 80) throw new IllegalArgumentException("Artifact limit");
        fields(node, Set.of("component", "props", "children"));
        String component = node.path("component").asString("");
        Set<String> properties = switch (component) {
            case "Card" -> Set.of("title");
            case "Text", "Heading", "Cell" -> Set.of("text");
            case "Metric" -> Set.of("label", "value");
            case "Table", "Row" -> Set.of();
            default -> throw new IllegalArgumentException("Unsupported artifact component");
        };
        var props = node.path("props");
        if (!props.isMissingNode()) {
            fields(props, properties);
            for (var value : props) {
                if (!value.isString() || value.asString().length() > 2048)
                    throw new IllegalArgumentException("Invalid artifact text");
            }
        }
        var children = node.path("children");
        if (children.isMissingNode()) return;
        if (!children.isArray() || children.size() > 24
                || !Set.of("Card", "Table", "Row").contains(component) && !children.isEmpty())
            throw new IllegalArgumentException("Invalid artifact children");
        for (var child : children) {
            String kind = child.path("component").asString("");
            if (component.equals("Table") && !kind.equals("Row") || component.equals("Row") && !kind.equals("Cell"))
                throw new IllegalArgumentException("Invalid artifact table");
            validate(child, depth + 1, count);
        }
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject() || !allowed.containsAll(node.propertyNames()))
            throw new IllegalArgumentException("Invalid artifact fields");
    }
}
