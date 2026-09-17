package io.memoryos.mcp;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** Safe MCP failures; causes stay in the diagnostic chain and never reach callers or the model. */
public final class McpException extends BusinessException {
    private McpException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    private McpException(String code, FailureCategory category, String message, Throwable cause) {
        super(code, category, message, message, cause);
    }

    public static McpException invalid(String message) {
        return new McpException("MCP_INVALID", FailureCategory.VALIDATION, message);
    }

    public static McpException notFound() {
        return new McpException("MCP_NOT_FOUND", FailureCategory.NOT_FOUND, "The MCP server or tool is not accessible.");
    }

    public static McpException conflict() {
        return new McpException("MCP_CONFLICT", FailureCategory.CONFLICT,
                "The MCP server changed. Reload it and try again.");
    }

    public static McpException slugTaken() {
        return new McpException("MCP_SLUG_TAKEN", FailureCategory.CONFLICT,
                "Another MCP server in this organization already uses this slug.");
    }

    public static McpException credentialRequired() {
        return new McpException("MCP_CREDENTIAL_REQUIRED", FailureCategory.CONFLICT,
                "Configure an administrator credential for this server before refreshing its tools.");
    }

    public static McpException toolSnapshotInvalid(String message) {
        return new McpException("MCP_TOOL_SNAPSHOT_INVALID", FailureCategory.VALIDATION, message);
    }

    public static McpException toolNameUnsupported() {
        return new McpException("MCP_TOOL_NAME_UNSUPPORTED", FailureCategory.VALIDATION,
                "This tool's name cannot be exposed to the model.");
    }

    public static McpException notConfigured() {
        return new McpException("MCP_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "MCP credentials are not configured. Contact the deployment owner.");
    }

    public static McpException credentialUnreadable() {
        return new McpException("MCP_CREDENTIAL_UNREADABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "A stored MCP credential cannot be read. Reconnect the server.");
    }

    public static McpException oauthDiscoveryFailed(String message) {
        return new McpException("MCP_OAUTH_DISCOVERY_FAILED", FailureCategory.VALIDATION, message);
    }

    public static McpException oauthNotConfigured() {
        return new McpException("MCP_OAUTH_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "MCP OAuth redirect URI is not configured. Contact the deployment owner.");
    }

    public static McpException oauthClientLabelTaken() {
        return new McpException("MCP_OAUTH_CLIENT_LABEL_TAKEN", FailureCategory.CONFLICT,
                "Another OAuth client of this server already uses this label.");
    }

    public static McpException oauthIssuerMismatch() {
        return new McpException("MCP_OAUTH_ISSUER_MISMATCH", FailureCategory.CONFLICT,
                "The authorization response did not come from the expected authorization server.");
    }

    public static McpException oauthRegistrationFailed() {
        return new McpException("MCP_OAUTH_REGISTRATION_FAILED", FailureCategory.SERVICE_UNAVAILABLE,
                "The authorization server did not register MemoryOS as a client.");
    }

    public static McpException oauthTokenFailed() {
        return new McpException("MCP_OAUTH_TOKEN_FAILED", FailureCategory.SERVICE_UNAVAILABLE,
                "The authorization server did not issue a usable token.");
    }

    public static McpException authorizationRequired() {
        return new McpException("MCP_AUTHORIZATION_REQUIRED", FailureCategory.CONFLICT,
                "Connect the MCP server before using its tools.");
    }

    public static McpException authorizationRequired(Throwable cause) {
        return new McpException("MCP_AUTHORIZATION_REQUIRED", FailureCategory.CONFLICT,
                "Connect the MCP server before using its tools.", cause);
    }

    public static McpException toolListTooLarge() {
        return new McpException("MCP_TOOL_LIST_TOO_LARGE", FailureCategory.VALIDATION,
                "The MCP server lists more tools than MemoryOS accepts.");
    }

    public static McpException timeout(Throwable cause) {
        return new McpException("MCP_TIMEOUT", FailureCategory.SERVICE_UNAVAILABLE,
                "The MCP server did not respond in time.", cause);
    }

    public static McpException unavailable(Throwable cause) {
        return new McpException("MCP_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "The MCP server is unavailable.", cause);
    }
}
