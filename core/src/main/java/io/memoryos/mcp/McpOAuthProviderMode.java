package io.memoryos.mcp;

/** Where OAuth endpoints come from; discovered endpoints are persisted during setup, never followed at runtime. */
public enum McpOAuthProviderMode {
    AUTO_DISCOVERY,
    KNOWN_PROVIDER
}
