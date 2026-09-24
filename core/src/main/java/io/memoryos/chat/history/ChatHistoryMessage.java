package io.memoryos.chat.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One message of a transcript, with the feedback its readers left and the titles it cited. */
public record ChatHistoryMessage(UUID id, String role, String content, @Nullable String modelName, Instant createdAt,
                                 @Nullable Boolean positive, @Nullable String comment, List<String> citations) {}
