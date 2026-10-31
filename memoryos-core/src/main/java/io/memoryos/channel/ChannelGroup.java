package io.memoryos.channel;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A group chat a bot is known in (MEM-273): the provider's chat id, the name the provider gives it (null on Zalo), the
 * administrator's own label for it, when it became known, when the bot last answered there (null for a Lark group
 * nobody has asked in yet, or a group waiting for permission), and when an administrator allowed the bot to answer
 * there, which only a group of a provider that cannot list its people needs (MEM-285).
 */
public record ChannelGroup(String chatId, @Nullable String name, @Nullable String label, Instant firstSeenAt,
                           @Nullable Instant lastMessageAt, @Nullable Instant allowedAt) {}
