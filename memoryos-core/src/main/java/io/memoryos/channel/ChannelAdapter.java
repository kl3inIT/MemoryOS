package io.memoryos.channel;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * One messaging platform, as the capability sees it: the adapter owns the transport, the provider's ids and the
 * shape of a reply; the rules, who a sender is, conversations and the turn are the capability's. There is one
 * adapter a {@link ChannelProvider}, in the provider's integration module.
 */
public interface ChannelAdapter {
    ChannelProvider provider();

    /**
     * Starts receiving for one bot. The adapter acknowledges each message to the provider and passes it to
     * {@code receiver}, which returns at once; it never calls the receiver for a message it could not read.
     *
     * @param secret the app's secret, decrypted for this call only
     */
    ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver);

    /**
     * Checks an app with the platform before it is stored: the credential works and the app has a bot.
     *
     * @throws ChannelException {@code CHANNEL_APP_REJECTED} when the platform refuses the credential or the app has
     *         no bot, {@code CHANNEL_PROVIDER_UNAVAILABLE} when it does not answer
     */
    ChannelVerification verify(ChannelRegion region, String appId, String secret);

    /** Opens the reply to one message, which quotes it. */
    ChannelReply reply(ChannelConnection connection, ChannelMessage message);

    /** Sends one short message in answer to another: a refusal, a notice. */
    void notice(ChannelConnection connection, ChannelMessage message, String text);

    /** The e-mail the provider holds for a sender; empty when it has none or will not say. */
    Optional<String> senderEmail(ChannelConnection connection, String senderId);

    /** Whether people outside the bot's organisation read the chat. */
    boolean externalChat(ChannelConnection connection, String chatId);
}
