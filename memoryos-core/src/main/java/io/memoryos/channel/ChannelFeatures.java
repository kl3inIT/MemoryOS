package io.memoryos.channel;

/**
 * What a provider's chats can do, as its adapter states it, so that the capability decides rather than the adapter
 * hiding it.
 *
 * @param organisations whether the provider names an organisation for each sender, by which a bot admits only its own
 * @param editsReply whether a reply can be rewritten while it is written; one that cannot gets the answer in pieces
 * @param maxText the longest text one message takes
 */
public record ChannelFeatures(boolean organisations, boolean editsReply, int maxText) {
    public ChannelFeatures {
        if (maxText < 1) throw new IllegalArgumentException("maxText must be positive");
    }
}
