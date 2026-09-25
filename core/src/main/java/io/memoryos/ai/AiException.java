package io.memoryos.ai;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/**
 * A model catalog or provider failure. The codes keep the {@code CHAT_} prefix they had while the catalog lived in
 * Chat: they are part of the HTTP problem contract the web client reads.
 */
public final class AiException extends BusinessException {
    private AiException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static AiException unavailable() {
        return new AiException("CHAT_UNAVAILABLE", FailureCategory.NOT_FOUND, "Chat is unavailable.");
    }

    public static AiException invalid(String message) {
        return new AiException("CHAT_INVALID_REQUEST", FailureCategory.VALIDATION, message);
    }

    public static AiException conflict() {
        return new AiException("CHAT_CONFLICT", FailureCategory.CONFLICT, "Chat changed or has an active reply.");
    }

    public static AiException busy() {
        return new AiException("CHAT_CAPACITY_EXCEEDED", FailureCategory.SERVICE_UNAVAILABLE, "Chat is busy. Retry later.");
    }

    public static AiException providerUnavailable() {
        return new AiException("CHAT_PROVIDER_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Chat provider is not configured or available.");
    }

    public static AiException providerCredentialRejected() {
        return new AiException("CHAT_PROVIDER_CREDENTIAL_REJECTED", FailureCategory.VALIDATION,
                "The provider rejected the API key.");
    }

    public static AiException providerUnreachable() {
        return new AiException("CHAT_PROVIDER_UNREACHABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "The provider endpoint could not be reached.");
    }

    public static AiException providerIncompatible() {
        return new AiException("CHAT_PROVIDER_INCOMPATIBLE", FailureCategory.VALIDATION,
                "The endpoint did not answer as an OpenAI-compatible API.");
    }
}
