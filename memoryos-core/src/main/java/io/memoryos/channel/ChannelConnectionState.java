package io.memoryos.channel;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What the process that holds a bot's connection last wrote of it. {@code heartbeatAt} is null when no process has
 * held it yet; an old one means none holds it now. {@code pairing} is whether the bot shows a code to scan that has
 * not expired, so it is open but not linked yet.
 */
public record ChannelConnectionState(@Nullable Instant openedAt, @Nullable String failureCode, @Nullable Instant failedAt,
                                     @Nullable Instant lastMessageAt, @Nullable Instant heartbeatAt, boolean pairing) {
    public static final ChannelConnectionState NONE = new ChannelConnectionState(null, null, null, null, null, false);
}
