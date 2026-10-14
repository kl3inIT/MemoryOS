package io.memoryos.integration.lark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelReply;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One answer written into a Lark card of the bot's own, which replies to the question and quotes it (MEM-246).
 *
 * <p>The card is built here rather than by the SDK's Markdown stream, whose card carries a fixed English header and
 * English placeholders. It has no header; the text shown while the answer starts is the bot's notice in its own
 * language. The card is created with streaming on, sent by its id, and its one Markdown element is rewritten with
 * the whole text so far: Lark animates the difference. Writes are paced by one writer thread, since every write to a
 * card must carry a sequence higher than the last and a burst of writes only meets Lark's rate limit.
 *
 * <p>Text the model wrote is made inert before it reaches the card: an {@code <at>} tag in an answer, or in a document
 * the answer quotes, would otherwise notify the people it names, the whole group for {@code <at id=all>}.
 */
final class LarkReplyCard implements ChannelReply {
    private static final Logger LOG = LoggerFactory.getLogger(LarkReplyCard.class);
    static final String ANSWER = "answer";
    /** How often the card is rewritten while the answer is written. */
    static final Duration PACE = Duration.ofMillis(800);
    /** Characters of an answer the card shows; a card holds at most 30 KB, and a character may take three bytes. */
    static final int LIMIT = 9_000;
    /** Lark refused a write because the card's streaming had ended, as it does after ten minutes. */
    static final int STREAMING_CLOSED = 300309;
    private static final int SUMMARY = 60;

    private final Client client;
    private final Object lock = new Object();
    private final StringBuilder text = new StringBuilder();
    private final String cardId;
    private final String messageId;
    private final Thread writer;
    private boolean ended;
    // Drawn only by the writer thread, then by the thread that ends the reply once the writer has stopped.
    private int sequence = 1;
    private String shown;

    /** Creates the card and sends it; a failure here leaves no message behind and is the caller's to report. */
    LarkReplyCard(Client client, ChannelMessage question, String waiting) throws Exception {
        this.client = client;
        this.shown = waiting;
        var created = client.cardkit().v1().card().create(CreateCardReq.newBuilder().createCardReqBody(
                CreateCardReqBody.newBuilder().type("card_json").data(card(waiting)).build()).build());
        if (created == null || !created.success() || created.getData() == null || created.getData().getCardId() == null) {
            throw new IllegalStateException("Lark refused the reply card: " + (created == null ? "no answer" : created.getCode()));
        }
        cardId = created.getData().getCardId();
        var content = new JsonObject();
        var data = new JsonObject();
        data.addProperty("card_id", cardId);
        content.addProperty("type", "card");
        content.add("data", data);
        // The same question always names the same reply, so a send Lark received but did not confirm is not doubled.
        var sent = client.im().v1().message().reply(ReplyMessageReq.newBuilder().messageId(question.messageId())
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder().msgType("interactive").content(content.toString())
                        .replyInThread(false)
                        .uuid(UUID.nameUUIDFromBytes(question.messageId().getBytes(StandardCharsets.UTF_8)).toString())
                        .build()).build());
        if (sent == null || !sent.success() || sent.getData() == null || sent.getData().getMessageId() == null) {
            throw new IllegalStateException("Lark refused the reply: " + (sent == null ? "no answer" : sent.getCode()));
        }
        messageId = sent.getData().getMessageId();
        writer = Thread.ofVirtual().name("lark-reply-" + cardId).start(this::write);
    }

    @Override
    public void append(String piece) {
        if (piece.isEmpty()) return;
        synchronized (lock) {
            text.append(piece);
        }
    }

    @Override
    public String finish(String answer, List<Source> sources, @Nullable String footer) {
        end();
        rewrite(markdown(cut(inert(answer)), sources, footer));
        close(summary(inert(answer)));
        return messageId;
    }

    @Override
    public void fail(String notice) {
        end();
        String partial;
        synchronized (lock) {
            partial = text.toString().strip();
        }
        // What was written stays; the notice says why it stops there.
        rewrite(partial.isEmpty() ? notice : cut(inert(partial)) + "\n\n" + notice);
        close(notice);
    }

    /** The writer: the whole text so far, at most once a pace, while it changes. */
    private void write() {
        while (true) {
            String now;
            synchronized (lock) {
                if (ended) return;
                try {
                    lock.wait(PACE.toMillis());
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (ended) return;
                now = text.toString();
            }
            if (!now.isBlank()) {
                String content = cut(inert(now));
                if (!content.equals(shown)) rewrite(content);
            }
        }
    }

    private void end() {
        synchronized (lock) {
            ended = true;
            lock.notifyAll();
        }
        try {
            writer.join(Duration.ofSeconds(35));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void rewrite(String content) {
        if (!rewrite(content, true)) return;
        shown = content;
    }

    private boolean rewrite(String content, boolean retry) {
        try {
            var answer = client.cardkit().v1().cardElement().content(ContentCardElementReq.newBuilder().cardId(cardId)
                    .elementId(ANSWER).contentCardElementReqBody(ContentCardElementReqBody.newBuilder()
                            .content(content).sequence(sequence++).build()).build());
            if (answer != null && answer.success()) return true;
            int code = answer == null ? -1 : answer.getCode();
            if (retry && code == STREAMING_CLOSED && settings(true, null)) return rewrite(content, false);
            failed("channel.lark.card_write_failed", code, null);
        } catch (Exception unwritten) {
            failed("channel.lark.card_write_failed", -1, unwritten);
        }
        return false;
    }

    private void close(String summary) {
        settings(false, summary);
    }

    private boolean settings(boolean streaming, @Nullable String summary) {
        var config = new JsonObject();
        config.addProperty("streaming_mode", streaming);
        config.addProperty("update_multi", true);
        if (summary != null) config.add("summary", content(summary));
        var settings = new JsonObject();
        settings.add("config", config);
        try {
            var answer = client.cardkit().v1().card().settings(SettingsCardReq.newBuilder().cardId(cardId)
                    .settingsCardReqBody(SettingsCardReqBody.newBuilder().settings(settings.toString())
                            .sequence(sequence++).build()).build());
            if (answer != null && answer.success()) return true;
            failed("channel.lark.card_settings_failed", answer == null ? -1 : answer.getCode(), null);
        } catch (Exception unwritten) {
            failed("channel.lark.card_settings_failed", -1, unwritten);
        }
        return false;
    }

    private static void failed(String event, int code, @Nullable Exception failure) {
        var log = LOG.atWarn().addKeyValue("event", event).addKeyValue("error_code", code);
        if (failure != null) log = log.addKeyValue("error_type", failure.getClass().getName());
        log.log("Lark reply card was not updated");
    }

    /** A card with no header, streaming on, and one Markdown element showing {@code waiting}. */
    static String card(String waiting) {
        var print = new JsonObject();
        var frequency = new JsonObject();
        frequency.addProperty("default", 30);
        var step = new JsonObject();
        step.addProperty("default", 2);
        print.add("print_frequency_ms", frequency);
        print.add("print_step", step);
        print.addProperty("print_strategy", "fast");
        var config = new JsonObject();
        config.addProperty("streaming_mode", true);
        config.addProperty("update_multi", true);
        config.add("summary", content(waiting));
        config.add("streaming_config", print);
        var element = new JsonObject();
        element.addProperty("tag", "markdown");
        element.addProperty("element_id", ANSWER);
        element.addProperty("content", waiting);
        var elements = new JsonArray();
        elements.add(element);
        var body = new JsonObject();
        body.add("elements", elements);
        var card = new JsonObject();
        card.addProperty("schema", "2.0");
        card.add("config", config);
        card.add("body", body);
        return card.toString();
    }

    private static JsonObject content(String text) {
        var content = new JsonObject();
        content.addProperty("content", text);
        return content;
    }

    /** Markdown of an answer: the text, its sources as links, then the closing line. */
    static String markdown(String text, List<Source> sources, @Nullable String footer) {
        var out = new StringBuilder(text.strip());
        if (!sources.isEmpty()) {
            out.append("\n\n");
            int index = 1;
            for (var source : sources) {
                String title = inert(source.title()).replace("[", "(").replace("]", ")");
                out.append(index++).append(". ");
                if (source.url() != null) out.append('[').append(title).append("](").append(source.url()).append(')');
                else out.append(title);
                out.append('\n');
            }
        }
        if (footer == null || footer.isBlank()) return out.toString().strip();
        return out.toString().strip() + "\n\n" + footer.strip();
    }

    /** Text with Lark's mention tags written out rather than obeyed. */
    static String inert(String text) {
        return text.replaceAll("(?i)<(/?)(at|person)\\b", "&lt;$1$2");
    }

    /** At most {@link #LIMIT} characters, marked when cut. */
    static String cut(String text) {
        return text.length() <= LIMIT ? text : text.substring(0, LIMIT).strip() + " …";
    }

    /** What the chat list shows for the reply: the answer's first line, without its Markdown. */
    static String summary(String answer) {
        String line = answer.strip().lines().findFirst().orElse("").replaceFirst("^>\\s*", "")
                .replaceAll("[*_`#\\[\\]]", "").strip();
        return line.length() <= SUMMARY ? line : line.substring(0, SUMMARY).strip() + "…";
    }
}
