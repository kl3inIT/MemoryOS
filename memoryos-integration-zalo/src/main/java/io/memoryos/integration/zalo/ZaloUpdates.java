package io.memoryos.integration.zalo;

import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelMessage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Zalo's updates as the capability's messages. An update is {@code {event_name, message}}; a webhook request wraps one
 * in Zalo's envelope ({@code {ok, result}}) and a poll answers one (seen on a real bot) or, defensively, several. A
 * message from a bot, or without the ids a message needs, is not handed over.
 *
 * <p>In a group Zalo pushes only the messages that name the bot (seen on a real bot, MEM-263 step 1), so every group
 * message addresses it. The mention is plain text, {@code @<display name>}, with no entity marking it; it is removed
 * from the question where it is found as a whole name.
 *
 * <p>A photo is read for its caption alone: a caption is asked as the question, with a note that the image was not
 * read, and a photo without one is not text.
 */
final class ZaloUpdates {
    static final String TEXT = "message.text.received";
    static final String IMAGE = "message.image.received";
    /** What the model is told after a caption, since only the caption reached it. */
    static final String IMAGE_NOT_READ = "(An image was attached to this message; the assistant cannot read images.)";

    private ZaloUpdates() {
    }

    /** The updates in a webhook request's body or a poll's result; {@code botName} is the name members type after @. */
    static List<ChannelMessage> messages(ChannelConnection connection, @Nullable String botName, JsonNode body) {
        JsonNode updates = body.has("result") ? body.path("result") : body;
        var messages = new ArrayList<ChannelMessage>();
        for (var update : ZaloBot.list(updates)) {
            var message = message(connection, botName, update);
            if (message != null) messages.add(message);
        }
        return messages;
    }

    static @Nullable ChannelMessage message(ChannelConnection connection, @Nullable String botName, JsonNode update) {
        var message = update.path("message");
        var from = message.path("from");
        var chat = message.path("chat");
        String messageId = ZaloBot.text(message, "message_id");
        String senderId = ZaloBot.text(from, "id");
        String chatId = ZaloBot.text(chat, "id");
        if (messageId == null || senderId == null || chatId == null || from.path("is_bot").asBoolean(false)) return null;
        boolean group = "GROUP".equalsIgnoreCase(ZaloBot.text(chat, "chat_type"));
        String event = ZaloBot.text(update, "event_name");
        String question = "";
        boolean text = false;
        if (TEXT.equals(event)) {
            question = nfc(ZaloBot.text(message, "text"));
            text = true;
        } else if (IMAGE.equals(event)) {
            String caption = nfc(ZaloBot.text(message, "caption"));
            if (!caption.isEmpty()) {
                question = caption;
                text = true;
            }
        }
        if (group && botName != null) question = withoutMention(question, "@" + nfc(botName));
        if (text && IMAGE.equals(event) && !question.isEmpty()) question = question + "\n\n" + IMAGE_NOT_READ;
        String sender = ZaloBot.text(from, "display_name");
        if (sender == null || sender.isBlank()) sender = ZaloBot.text(from, "name");
        return new ChannelMessage(connection.id(), messageId, chatId,
                group ? ChannelMessage.Kind.GROUP : ChannelMessage.Kind.DIRECT, senderId, sender, null, true,
                text ? ChannelMessage.Type.TEXT : ChannelMessage.Type.OTHER, question, null);
    }

    /**
     * The text without the first whole mention of the bot: matched ignoring case on the same normalised form, and only
     * where the name ends at a space, punctuation or the end, so {@code @Bot} is not taken out of {@code @Bota}; the
     * spaces it leaves are folded.
     */
    static String withoutMention(String text, String mention) {
        for (int at = text.indexOf('@'); at >= 0; at = text.indexOf('@', at + 1)) {
            int end = at + mention.length();
            if (!text.regionMatches(true, at, mention, 0, mention.length())) continue;
            if (end < text.length() && Character.isLetterOrDigit(text.codePointAt(end))) continue;
            return (text.substring(0, at) + " " + text.substring(end)).replaceAll("[ \\t]{2,}", " ").strip();
        }
        return text;
    }

    private static String nfc(@Nullable String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }
}
