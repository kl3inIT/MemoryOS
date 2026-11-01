package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The filters the screen offers; every one narrows the same page query.
 *
 * @param blocked only conversations with a reply the guardrails stopped (`refusal_reason = blocked_topic`), a topic or
 *                a blocked phrase, in the question or the answer
 */
public record ChatHistoryQuery(@Nullable Instant from, @Nullable Instant to, @Nullable String text, @Nullable UUID actorId,
                               @Nullable ChatHistoryFeedback feedback, boolean blocked) {
    /** Every conversation the reader may see. */
    public static final ChatHistoryQuery ALL = new ChatHistoryQuery(null, null, null, null, null, false);
}
