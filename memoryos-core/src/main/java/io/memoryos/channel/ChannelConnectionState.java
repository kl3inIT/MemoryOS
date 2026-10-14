package io.memoryos.channel;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What the process that holds a bot's connection last wrote of it. {@code heartbeatAt} is null when no process has
 * held it yet; an old one means none holds it now.
 */
public record ChannelConnectionState(@Nullable Instant openedAt, @Nullable String failureCode, @Nullable Instant failedAt,
                                     @Nullable Instant lastMessageAt, @Nullable Instant heartbeatAt) {
    public static final ChannelConnectionState NONE = new ChannelConnectionState(null, null, null, null, null);
}
