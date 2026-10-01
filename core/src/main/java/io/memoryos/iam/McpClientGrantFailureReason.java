package io.memoryos.iam;

import io.memoryos.FailureCategory;
import io.memoryos.FailureReason;

public enum McpClientGrantFailureReason implements FailureReason {
    NOT_FOUND(
            "MCP_CLIENT_GRANT_NOT_FOUND",
            FailureCategory.NOT_FOUND,
            "The connected app was not found."
    ),
    UNAVAILABLE(
            "MCP_CLIENT_GRANT_UNAVAILABLE",
            FailureCategory.SERVICE_UNAVAILABLE,
            "Connected apps are temporarily unavailable."
    );

    private final String code;
    private final FailureCategory category;
    private final String message;

    McpClientGrantFailureReason(String code, FailureCategory category, String message) {
        this.code = code;
        this.category = category;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public FailureCategory category() {
        return category;
    }

    @Override
    public String message() {
        return message;
    }
}
