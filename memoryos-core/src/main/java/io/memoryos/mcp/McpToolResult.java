package io.memoryos.mcp;

import java.util.Objects;

/** Model-facing text of one tool call; {@code error} is the server's {@code isError}, not a transport failure. */
public record McpToolResult(String text, boolean error) {
    public McpToolResult {
        Objects.requireNonNull(text, "text must not be null");
    }
}
