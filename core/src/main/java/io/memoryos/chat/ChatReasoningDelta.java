package io.memoryos.chat;

import org.jspecify.annotations.Nullable;

/**
 * Provider-exposed reasoning text (for example an OpenAI reasoning summary); providers without it emit nothing.
 * Reasoning of a deep research agent carries the agent call as {@code parentToolCallId}.
 */
public record ChatReasoningDelta(String text, @Nullable String parentToolCallId) implements ChatActivityEvent {
    public ChatReasoningDelta(String text) {
        this(text, null);
    }

    public ChatReasoningDelta {
        if (text == null || text.isEmpty() || text.length() > ChatActivity.MAX_REASONING
                || parentToolCallId != null && !ChatToolEvent.validId(parentToolCallId))
            throw new IllegalArgumentException("Invalid reasoning delta");
    }
}
