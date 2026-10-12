package io.memoryos.mcp;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A tool as the server lists it; annotations are hints from the server, not enforced guarantees. */
public record McpToolDescriptor(
        String name,
        @Nullable String title,
        String description,
        Map<String, Object> inputSchema,
        @Nullable Boolean readOnlyHint,
        @Nullable Boolean destructiveHint,
        @Nullable Boolean idempotentHint,
        @Nullable Boolean openWorldHint
) {
    public McpToolDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of("type", "object") : Map.copyOf(inputSchema);
    }
}
