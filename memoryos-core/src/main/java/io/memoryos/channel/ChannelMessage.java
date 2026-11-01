package io.memoryos.channel;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A message as an adapter hands it over, in the provider's own ids.
 *
 * @param messageId the provider's id of the message, which is what a message pushed again shares
 * @param senderName what the provider shows as the sender's name
 * @param organisations the sender's organisation and the bot's, as the provider names them; null for a provider
 *        without organisations
 * @param addressed whether the bot was named; a direct message always addresses it
 * @param text the text with the bot's mention removed; empty for a message that is not text
 * @param repliesTo the provider's id of the message this one replies to, in a thread or by quoting
 */
public record ChannelMessage(UUID connectionId, String messageId, String chatId, Kind kind, String senderId,
                             @Nullable String senderName, @Nullable Organisations organisations, boolean addressed,
                             Type type, String text, @Nullable String repliesTo) {
    public enum Kind { DIRECT, GROUP }

    public enum Type { TEXT, OTHER }

    /**
     * @param sender the provider organisation of the sender, empty when the event names none
     * @param bot the provider organisation the bot's app belongs to, as the event names it, empty when it names none
     */
    public record Organisations(String sender, String bot) {}
}
