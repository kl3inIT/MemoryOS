package io.memoryos.voice;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/**
 * A voice connection or speech provider failure. The codes keep the {@code CHAT_} prefix they had while voice lived
 * in Chat: they are part of the HTTP problem contract and the WebSocket close reasons the web client reads.
 */
public final class VoiceException extends BusinessException {
    private VoiceException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static VoiceException unavailable() {
        return new VoiceException("CHAT_UNAVAILABLE", FailureCategory.NOT_FOUND, "Chat is unavailable.");
    }

    public static VoiceException invalid(String message) {
        return new VoiceException("CHAT_INVALID_REQUEST", FailureCategory.VALIDATION, message);
    }

    public static VoiceException conflict() {
        return new VoiceException("CHAT_CONFLICT", FailureCategory.CONFLICT, "Chat changed or has an active reply.");
    }

    public static VoiceException busy() {
        return new VoiceException("CHAT_CAPACITY_EXCEEDED", FailureCategory.SERVICE_UNAVAILABLE, "Chat is busy. Retry later.");
    }

    public static VoiceException providerUnavailable() {
        return new VoiceException("CHAT_PROVIDER_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Chat provider is not configured or available.");
    }
}
