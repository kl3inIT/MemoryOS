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

    /** The Tenant turned administrative history off; the conversations exist, this reader may not read them. */
    public static ChatException historyDisabled() {
        return new ChatException("CHAT_HISTORY_DISABLED", FailureCategory.NOT_PERMITTED,
                "Conversation history is turned off for this organization.");
    }

    public static ChatException invalid(String message) {
        return new ChatException("CHAT_INVALID_REQUEST", FailureCategory.VALIDATION, message);
    }

    public static ChatException conflict() {
        return new ChatException("CHAT_CONFLICT", FailureCategory.CONFLICT, "Chat changed or has an active reply.");
    }

    /** The owner's file library is at its storage limit; the caller's own numbers say by how much. */
    public static ChatException storageFull(long usedBytes, long limitBytes) {
        return new ChatException("CHAT_STORAGE_FULL", FailureCategory.CONFLICT,
                "The file library is full: " + usedBytes + " of " + limitBytes + " bytes are used.");
    }

    public static ChatException busy() {
        return new ChatException("CHAT_CAPACITY_EXCEEDED", FailureCategory.SERVICE_UNAVAILABLE, "Chat is busy. Retry later.");
    }

    public static ChatException providerUnavailable() {
        return new ChatException("CHAT_PROVIDER_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Chat provider is not configured or available.");
    }

    /** The selected model cannot run Deep research: it lacks tool calling or the minimum context window. */
    public static ChatException providerCredentialRejected() {
        return new ChatException("CHAT_PROVIDER_CREDENTIAL_REJECTED", FailureCategory.VALIDATION,
                "The provider rejected the API key.");
    }

    public static ChatException providerUnreachable() {
        return new ChatException("CHAT_PROVIDER_UNREACHABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "The provider endpoint could not be reached.");
    }

    public static ChatException providerIncompatible() {
        return new ChatException("CHAT_PROVIDER_INCOMPATIBLE", FailureCategory.VALIDATION,
                "The endpoint did not answer as an OpenAI-compatible API.");
    }

    public static ChatException researchModelUnsupported() {
        return new ChatException("CHAT_RESEARCH_MODEL_UNSUPPORTED", FailureCategory.VALIDATION,
                "The selected model cannot run Deep research.");
    }

    /** Deep research was requested while the Tenant administrator has turned it off. */
    public static ChatException researchUnavailable() {
        return new ChatException("CHAT_RESEARCH_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Deep research is not available.");
    }

    /** Web search was requested but this Tenant has no usable Web connection or a tool-capable model. */
    public static ChatException webUnavailable() {
        return new ChatException("CHAT_WEB_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Web search is not configured or available.");
    }
}
