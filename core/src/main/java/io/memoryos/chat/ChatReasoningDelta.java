package io.memoryos.chat;

/** Provider-exposed reasoning text (for example an OpenAI reasoning summary); providers without it emit nothing. */
public record ChatReasoningDelta(String text) implements ChatActivityEvent {
    public ChatReasoningDelta {
        if (text == null || text.isEmpty() || text.length() > ChatActivity.MAX_REASONING)
            throw new IllegalArgumentException("Invalid reasoning delta");
    }
}
