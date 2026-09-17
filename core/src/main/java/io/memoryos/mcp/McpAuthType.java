package io.memoryos.mcp;

/** How MemoryOS authenticates to an MCP server. Pass-through OAuth is gated to a later phase. */
public enum McpAuthType {
    NONE,
    API_TOKEN,
    OAUTH
}
