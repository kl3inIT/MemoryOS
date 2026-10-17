package io.memoryos.channel;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A group chat a bot has answered in (MEM-273): the provider's chat id, the name the provider gives it (null on Zalo),
 * the administrator's own label for it, and when the bot first and last answered there.
 */
public record ChannelGroup(String chatId, @Nullable String name, @Nullable String label, Instant firstSeenAt,
                           Instant lastMessageAt) {}
