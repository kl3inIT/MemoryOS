package io.memoryos.channel;

/** What an administrator gives to add a bot. The credentials' secret is the platform app's, kept encrypted. */
public record ChannelConnectionDraft(ChannelProvider provider, String name, ChannelCredentials credentials,
                                     String noticeLanguage) {
}
