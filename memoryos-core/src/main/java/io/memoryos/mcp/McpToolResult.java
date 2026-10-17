package io.memoryos.mcp;

import java.util.Objects;

/**
 * One tool call: {@code text} is what the model reads, {@code error} the server's {@code isError}, not a transport
 * failure. {@code result} is the whole {@code CallToolResult} as JSON, with its structured content and {@code _meta},
 * which only an MCP Apps view reads (MEM-275).
 */
public record McpToolResult(String text, boolean error, String result) {
    public McpToolResult {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(result, "result must not be null");
    }
}
