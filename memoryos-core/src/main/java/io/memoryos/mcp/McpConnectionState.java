package io.memoryos.mcp;

/** How a User stands with one MCP server. */
public enum McpConnectionState {
    /** The server needs no credential. */
    NOT_REQUIRED,
    /** The administrator's shared connection is used; the server status reports it. */
    SHARED,
    /** The User has not connected their own account yet. */
    NOT_CONNECTED,
    /** The User's own credential is usable. */
    CONNECTED,
    /** The User's own credential was rejected and must be authorized again. */
    REAUTH_REQUIRED
}
