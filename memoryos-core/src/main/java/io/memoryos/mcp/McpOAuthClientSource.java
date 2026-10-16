package io.memoryos.mcp;

/** How an OAuth client was obtained: entered by an administrator, dynamically registered, or a Client ID Metadata Document. */
public enum McpOAuthClientSource {
    ADMIN,
    REGISTERED,
    METADATA_DOCUMENT
}
