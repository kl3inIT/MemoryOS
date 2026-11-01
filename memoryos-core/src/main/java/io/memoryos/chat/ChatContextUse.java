package io.memoryos.chat;

/**
 * What the last model call of a reply carried, against the model's input limit (MEM-267), so the composer can show how
 * full the conversation is before a turn fails on it. Tokens are the guard's estimate of the request, not a provider
 * count.
 */
public record ChatContextUse(int tokens, int limit) {
    public ChatContextUse {
        if (tokens < 0 || limit <= 0) throw new IllegalArgumentException("Invalid context use");
    }
}
