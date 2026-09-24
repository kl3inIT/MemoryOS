package io.memoryos.chat.history;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The filters the screen offers; every one narrows the same page query. */
public record ChatHistoryQuery(@Nullable Instant from, @Nullable Instant to, @Nullable String text, @Nullable UUID actorId,
                               @Nullable ChatHistoryFeedback feedback) {}
