package io.memoryos.channel;

import org.jspecify.annotations.NonNull;

/** What an administrator gives to add a bot. The secret is the platform app's, kept encrypted. */
public record ChannelConnectionDraft(ChannelProvider provider, String name, ChannelRegion region, String appId,
                                     String secret, String noticeLanguage) {
    @Override
    public @NonNull String toString() {
        return "ChannelConnectionDraft[provider=" + provider + ", name=" + name + ", region=" + region + ", appId=" + appId
                + ", secret=redacted, noticeLanguage=" + noticeLanguage + "]";
    }
}
