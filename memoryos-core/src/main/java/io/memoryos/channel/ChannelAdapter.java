package io.memoryos.channel;

import java.util.Map;
import java.util.function.Consumer;

/**
 * One messaging platform, as the capability sees it: the adapter owns the transport, the provider's ids and the
 * shape of a reply; the rules, who a sender is, conversations and the turn are the capability's. There is one
 * adapter a {@link ChannelProvider}, in the provider's integration module. What the provider's chats can do is
 * stated by {@link #features()}, not hidden in the adapter.
 */
public interface ChannelAdapter {
    ChannelProvider provider();

    ChannelFeatures features();

    /**
     * Starts receiving for one bot: a transport that keeps a connection of its own, or one the provider pushes to
     * through its {@link ChannelTransport#webhook()}. The adapter acknowledges each message to the provider and passes
     * it to {@code receiver}, which returns at once; it never calls the receiver for a message it could not read.
     *
     * @param secret the app's secret, decrypted for this call only
     */
    ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver);

    /**
     * Checks an app with the platform before it is stored: the credentials are well formed, they work and the app has
     * a bot. The answer carries the provider's id of the app, normalised.
     *
     * @throws ChannelException {@code CHANNEL_INVALID} when the credentials are not the provider's shape,
     *         {@code CHANNEL_APP_REJECTED} when the platform refuses them or the app has no bot,
     *         {@code CHANNEL_PROVIDER_UNAVAILABLE} when it does not answer
     */
    ChannelVerification verify(ChannelCredentials credentials);

    /**
     * A bot's public settings as they are kept: the given ones checked and completed with the provider's defaults.
     * Asked when a bot is added and when an administrator changes one.
     *
     * @throws ChannelException {@code CHANNEL_INVALID} for a setting the provider does not have or a value it does not
     *         take, or a typed code for a value this deployment cannot serve
     */
    Map<String, String> settings(Map<String, String> requested);

    /**
     * Marks a message as taken while it waits for the turn its conversation is already running. Best effort: a mark
     * that cannot be put is logged and the message is still answered.
     */
    void acknowledge(ChannelConnection connection, ChannelMessage message);

    /** Opens the reply to one message, which quotes it where the provider can. */
    ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting);

    /** Sends one short message in answer to another: a refusal, a notice. */
    void notice(ChannelConnection connection, ChannelMessage message, String text);

    /** What the provider says of a sender; {@link ChannelSender#UNKNOWN} when it says nothing. */
    ChannelSender sender(ChannelConnection connection, String senderId);

    /**
     * What the provider says of a group chat: whether people outside the bot's organisation read it (false for a
     * provider without organisations) and its name, which the bot's page shows (MEM-273).
     */
    ChannelChat chat(ChannelConnection connection, String chatId);
}
