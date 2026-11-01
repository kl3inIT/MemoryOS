package io.memoryos.chat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Bounded tree navigation metadata; message content is loaded from the selected branch. */
public record ChatBranch(UUID id, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId) {}
