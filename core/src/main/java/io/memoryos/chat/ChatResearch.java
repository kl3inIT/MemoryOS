package io.memoryos.chat;

import org.jspecify.annotations.Nullable;

/**
 * Deep research state of an assistant message. {@code clarification} mirrors Onyx {@code chat_message.is_clarification}:
 * the next research turn skips clarification. The plan is kept so a reload shows it, a departure from Onyx.
 */
public record ChatResearch(boolean clarification, @Nullable String plan) {
    /** Stored plan characters, below the {@code chat_message.research_plan} column check. */
    public static final int MAX_PLAN = 100_000;
    public static final ChatResearch EMPTY = new ChatResearch(false, null);

    public ChatResearch {
        if (plan != null && (plan.isEmpty() || plan.length() > MAX_PLAN))
            throw new IllegalArgumentException("Invalid research plan");
    }
}
