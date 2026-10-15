package io.memoryos.channel;

/** The messaging platform a bot lives on. */
public enum ChannelProvider {
    LARK,
    /** The Zalo Bot Platform, a bot made in Zalo Bot Creator; not a Zalo Official Account, whose API differs (MEM-263). */
    ZALO_BOT
}
