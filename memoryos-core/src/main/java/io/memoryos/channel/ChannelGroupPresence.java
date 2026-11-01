package io.memoryos.channel;

import org.jspecify.annotations.Nullable;

/** A group chat a provider lists the bot in (MEM-273): its id and its name, null when the provider gives none. */
public record ChannelGroupPresence(String chatId, @Nullable String name) {}
