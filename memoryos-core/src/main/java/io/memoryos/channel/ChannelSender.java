package io.memoryos.channel;

import org.jspecify.annotations.Nullable;

/** What the provider says of a sender: the e-mail it holds, null when it has none or will not say. */
public record ChannelSender(@Nullable String email) {
    public static final ChannelSender UNKNOWN = new ChannelSender(null);
}
