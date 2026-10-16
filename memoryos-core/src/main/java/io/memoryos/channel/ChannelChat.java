package io.memoryos.channel;

import org.jspecify.annotations.Nullable;

/**
 * What a provider says of a group chat: whether people outside the bot's organisation read it, and its name, null
 * when the provider gives none (Zalo).
 */
public record ChannelChat(boolean external, @Nullable String name) {
    /** A chat of a provider without organisations or names. */
    public static final ChannelChat UNNAMED = new ChannelChat(false, null);
}
