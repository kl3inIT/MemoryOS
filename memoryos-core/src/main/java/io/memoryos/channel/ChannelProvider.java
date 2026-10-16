package io.memoryos.channel;

/** The messaging platform a bot lives on. */
public enum ChannelProvider {
    LARK("Lark"),
    /** The Zalo Bot Platform, a bot made in Zalo Bot Creator; not a Zalo Official Account, whose API differs (MEM-263). */
    ZALO_BOT("Zalo");

    private final String platform;

    ChannelProvider(String platform) {
        this.platform = platform;
    }

    /** The platform's name as its members know it, which the answer model is told. */
    public String platform() {
        return platform;
    }
}
