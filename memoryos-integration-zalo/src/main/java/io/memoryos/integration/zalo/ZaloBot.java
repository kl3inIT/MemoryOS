package io.memoryos.integration.zalo;

import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One Zalo bot's calls, by its token. A poll waits up to thirty seconds at Zalo, so it has a client with a longer
 * deadline than the others. Every failure is a {@link ZaloApiException} by code: Zalo's own error in a 200 envelope,
 * a failed status, or no answer.
 */
final class ZaloBot {
    /** How long Zalo holds a poll that gets nothing. */
    static final Duration POLL_WAIT = Duration.ofSeconds(30);
    private static final Limits CALL = new Limits(Duration.ofSeconds(15), 1_048_576);
    private static final Limits POLL = new Limits(POLL_WAIT.plusSeconds(15), 1_048_576);
    /** A typing mark is worth little late, so it waits briefly, as OpenClaw's. */
    private static final Limits MARK = new Limits(Duration.ofSeconds(5), 65_536);
    private static final int ERROR_BODY_BYTES = 4_096;
    // Seen on a real bot (MEM-263 step 1): what Zalo answers when there is no webhook to delete or to describe.
    private static final int NO_WEBHOOK_TO_DELETE = 400;
    private static final int NO_WEBHOOK_SET = 404;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ZaloBotApi calls;
    private final ZaloBotApi polls;
    private final ZaloBotApi marks;

    ZaloBot(String baseUrl, String token) {
        String root = baseUrl.replaceAll("/+$", "") + "/bot" + token;
        this.calls = OutboundHttp.service(ZaloBotApi.class, client(root, CALL));
        this.polls = OutboundHttp.service(ZaloBotApi.class, client(root, POLL));
        this.marks = OutboundHttp.service(ZaloBotApi.class, client(root, MARK));
    }

    /** A failed status is read for Zalo's own code, within a small bound; its text is never kept. */
    private static RestClient client(String root, Limits limits) {
        return OutboundHttp.builder(limits, (request, response) -> {
            Integer code = null;
            try (InputStream body = response.getBody()) {
                code = errorCode(JSON.readTree(body.readNBytes(ERROR_BODY_BYTES)));
            } catch (IOException | JacksonException unreadable) {
                // The status is the code.
            }
            throw new ZaloApiException(code != null ? code : response.getStatusCode().value());
        }).baseUrl(root).build();
    }

    record Me(String id, @Nullable String name) {}

    /** Zalo's check of a webhook address: whether its call arrived, and its typed outcome such as {@code webhook.err.dns}. */
    record Check(boolean ok, @Nullable String outcome) {}

    /** The bot's name is the one members see and type after {@code @}, its display name; its account name is a handle. */
    Me me() {
        var result = result(() -> calls.getMe(Map.of()));
        String name = text(result, "display_name");
        return new Me(Objects.requireNonNullElse(text(result, "id"), ""), name != null ? name : text(result, "account_name"));
    }

    /** The updates Zalo has, waiting for one up to {@link #POLL_WAIT}; none when the wait ran out. */
    List<JsonNode> poll() {
        try {
            return list(result(() -> polls.getUpdates(Map.of("timeout", String.valueOf(POLL_WAIT.toSeconds())))));
        } catch (ZaloApiException failure) {
            if (failure.idle()) return List.of();
            throw failure;
        }
    }

    /**
     * Sets the address Zalo pushes to. Zalo calls it to check it before answering; the check's outcome is in the answer
     * when Zalo gives one.
     */
    @Nullable Check setWebhook(String url, String secret) {
        var result = result(() -> calls.setWebhook(Map.of("url", url, "secret_token", secret)));
        var verification = result.path("verification");
        return verification.isObject() ? check(verification) : null;
    }

    Check testWebhook() {
        return check(result(() -> calls.testWebhook(Map.of())));
    }

    /** Removes the webhook; Zalo answers 400 when none is set, which is what was asked. */
    void deleteWebhook() {
        try {
            result(() -> calls.deleteWebhook(Map.of()));
        } catch (ZaloApiException failure) {
            if (failure.code() != NO_WEBHOOK_TO_DELETE) throw failure;
        }
    }

    /** The address Zalo pushes to now; empty when none is set, which Zalo answers with 404. */
    String webhookUrl() {
        try {
            return Objects.requireNonNullElse(text(result(() -> calls.getWebhookInfo(Map.of())), "url"), "");
        } catch (ZaloApiException failure) {
            if (failure.code() == NO_WEBHOOK_SET) return "";
            throw failure;
        }
    }

    /** @return Zalo's id of the message sent, or null when it gives none */
    @Nullable String send(String chatId, String text, boolean markdown) {
        var body = new HashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (markdown) body.put("parse_mode", "markdown");
        return text(result(() -> calls.sendMessage(body)), "message_id");
    }

    void typing(String chatId) {
        result(() -> marks.sendChatAction(Map.of("chat_id", chatId, "action", "typing")));
    }

    /** The result of an envelope Zalo marked ok; anything else is its error code. */
    private static JsonNode result(Supplier<@Nullable JsonNode> call) {
        JsonNode envelope;
        try {
            envelope = call.get();
        } catch (ZaloApiException failure) {
            throw failure;
        } catch (RestClientException unanswered) {
            // Its message names the address, which holds the token, so neither it nor its cause goes further.
            throw new ZaloApiException(ZaloApiException.NO_ANSWER);
        }
        if (envelope == null || !envelope.path("ok").asBoolean(false)) {
            Integer code = envelope == null ? null : errorCode(envelope);
            throw new ZaloApiException(code != null ? code : ZaloApiException.NO_ANSWER);
        }
        return envelope.path("result");
    }

    private static @Nullable Integer errorCode(JsonNode envelope) {
        var code = envelope.path("error_code");
        return code.isNumber() || code.isString() && code.asString("").matches("-?[0-9]{1,9}") ? code.asInt() : null;
    }

    private static Check check(JsonNode node) {
        return new Check(node.path("ok").asBoolean(false), text(node, "outcome"));
    }

    /** One update or several, as Zalo sends either. */
    static List<JsonNode> list(JsonNode node) {
        if (node.isArray()) {
            var items = new ArrayList<JsonNode>();
            node.forEach(items::add);
            return items;
        }
        return node.isObject() && !node.isEmpty() ? List.of(node) : List.of();
    }

    /** A field's text, a number written out; null when the field is absent or of another kind. */
    static @Nullable String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() || value.isNumber() ? value.asString("") : null;
    }
}
