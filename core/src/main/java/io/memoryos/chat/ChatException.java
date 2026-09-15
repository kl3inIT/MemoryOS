package io.memoryos.chat;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class ChatException extends BusinessException {
    private ChatException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static ChatException unavailable() {
        return new ChatException("CHAT_UNAVAILABLE", FailureCategory.NOT_FOUND, "Chat is unavailable.");
    }

    public static ChatException invalid(String message) {
        return new ChatException("CHAT_INVALID_REQUEST", FailureCategory.VALIDATION, message);
    }

    public static ChatException conflict() {
        return new ChatException("CHAT_CONFLICT", FailureCategory.CONFLICT, "Chat changed or has an active reply.");
    }

    public static ChatException busy() {
        return new ChatException("CHAT_CAPACITY_EXCEEDED", FailureCategory.SERVICE_UNAVAILABLE, "Chat is busy. Retry later.");
    }

    public static ChatException providerUnavailable() {
        return new ChatException("CHAT_PROVIDER_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Chat provider is not configured or available.");
    }

    /** Web search was requested but this Tenant has no usable Web connection or a tool-capable model. */
    public static ChatException webUnavailable() {
        return new ChatException("CHAT_WEB_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Web search is not configured or available.");
    }
}
