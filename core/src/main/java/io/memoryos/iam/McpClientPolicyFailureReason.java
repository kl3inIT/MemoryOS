package io.memoryos.iam;

import io.memoryos.FailureCategory;
import io.memoryos.FailureReason;

public enum McpClientPolicyFailureReason implements FailureReason {
    UNAVAILABLE(
            "MCP_CLIENT_POLICY_UNAVAILABLE",
            FailureCategory.SERVICE_UNAVAILABLE,
            "Trusted apps cannot be changed right now."
    );

    private final String code;
    private final FailureCategory category;
    private final String message;

    McpClientPolicyFailureReason(String code, FailureCategory category, String message) {
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
