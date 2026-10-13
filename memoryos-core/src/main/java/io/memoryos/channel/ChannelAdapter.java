package io.memoryos.channel;

import java.util.Optional;

/**
 * One messaging platform, as the capability sees it: the adapter owns the transport, the provider's ids and the
 * shape of a reply; the rules, who a sender is, conversations and the turn are the capability's. There is one
 * adapter a {@link ChannelProvider}, in the provider's integration module.
 */
public interface ChannelAdapter {
    ChannelProvider provider();

    /** Opens the reply to one message, which quotes it. */
    ChannelReply reply(ChannelConnection connection, ChannelMessage message);

    /** Sends one short message in answer to another: a refusal, a notice. */
    void notice(ChannelConnection connection, ChannelMessage message, String text);

    /** The e-mail the provider holds for a sender; empty when it has none or will not say. */
    Optional<String> senderEmail(ChannelConnection connection, String senderId);

    /** Whether people outside the bot's organisation read the chat. */
    boolean externalChat(ChannelConnection connection, String chatId);
}
