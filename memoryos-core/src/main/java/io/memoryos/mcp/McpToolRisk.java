package io.memoryos.mcp;

/**
 * What a tool may do to data, read from its MCP annotations: {@code readOnlyHint: true} reads, an explicit
 * {@code destructiveHint: true} may overwrite or delete, and anything else writes (MEM-264).
 */
public enum McpToolRisk {
    READ,
    WRITE,
    DESTRUCTIVE
}
