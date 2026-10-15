package io.memoryos.integration.zalo;

import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Zalo's updates as the capability's messages. An update is {@code {event_name, message}}; a webhook request wraps one
 * in Zalo's envelope ({@code {ok, result}}) and a poll answers one or several. A message from a bot, or without the ids
 * a message needs, is not handed over.
 *
 * <p>Zalo documents no mention in a group (MEM-263 step 1 settles it on a real bot): until then a group message
 * addresses the bot when its text names it as {@code @<bot name>}, which is removed from the question.
 */
final class ZaloUpdates {
    static final String TEXT = "message.text.received";

    private ZaloUpdates() {
    }

    /** The updates in a webhook request's body or a poll's result. */
    static List<ChannelMessage> messages(ChannelConnection connection, JsonNode body) {
        JsonNode updates = body.has("result") ? body.path("result") : body;
        var messages = new ArrayList<ChannelMessage>();
        for (var update : ZaloBot.list(updates)) {
            var message = message(connection, update);
            if (message != null) messages.add(message);
        }
        return messages;
    }

    static @Nullable ChannelMessage message(ChannelConnection connection, JsonNode update) {
        var message = update.path("message");
        var from = message.path("from");
        var chat = message.path("chat");
        String messageId = ZaloBot.text(message, "message_id");
        String senderId = ZaloBot.text(from, "id");
        String chatId = ZaloBot.text(chat, "id");
        if (messageId == null || senderId == null || chatId == null || from.path("is_bot").asBoolean(false)) return null;
        boolean group = "GROUP".equalsIgnoreCase(ZaloBot.text(chat, "chat_type"));
        boolean text = TEXT.equals(ZaloBot.text(update, "event_name"));
        String body = text ? ZaloBot.text(message, "text") : null;
        String question = body == null ? "" : body.strip();
        boolean addressed = !group;
        if (group && connection.botName() != null) {
            String mention = "@" + connection.botName();
            int at = question.toLowerCase(Locale.ROOT).indexOf(mention.toLowerCase(Locale.ROOT));
            if (at >= 0) {
                addressed = true;
                question = (question.substring(0, at) + question.substring(at + mention.length())).strip();
            }
        }
        return new ChannelMessage(connection.id(), messageId, chatId,
                group ? ChannelMessage.Kind.GROUP : ChannelMessage.Kind.DIRECT, senderId,
                ZaloBot.text(from, "display_name"), null, addressed,
                text ? ChannelMessage.Type.TEXT : ChannelMessage.Type.OTHER, question, null);
    }
}
