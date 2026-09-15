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

    public static McpException notConfigured() {
        return new McpException("MCP_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "MCP credentials are not configured. Contact the deployment owner.");
    }

    public static McpException credentialUnreadable() {
        return new McpException("MCP_CREDENTIAL_UNREADABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "A stored MCP credential cannot be read. Reconnect the server.");
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
