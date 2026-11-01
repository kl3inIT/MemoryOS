package io.memoryos.integration.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.Client;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelReply;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LarkReplyCardTest {
    private static final ChannelMessage QUESTION = new ChannelMessage(UUID.randomUUID(), "om_question", "oc_chat",
            ChannelMessage.Kind.DIRECT, "ou_sender", "Hà", new ChannelMessage.Organisations("org", "org"), true, ChannelMessage.Type.TEXT, "Bao nhiêu ngày phép?", null);

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger closedWrites = new AtomicInteger();
    private HttpServer server;
    private LarkTransport transport;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (transport != null) transport.close();
    }

    @Test
    void theCardHasNoHeaderStreamsTheAnswerAndClosesWithItsSources() throws Exception {
        var card = new LarkReplyCard(client(), QUESTION, "Đang trả lời…");
        card.append("Bạn có ");
        card.append("12 ngày phép <at id=all></at>.");
        Thread.sleep(LarkReplyCard.PACE.multipliedBy(2).toMillis());
        String id = card.finish(List.of("Bạn có **12 ngày** phép <at id=all></at>."),
                List.of(new ChannelReply.Source("Quy chế", "https://tasco.larksuite.com/wiki/abc")), "Trả lời vào tin này để hỏi tiếp.");

        assertEquals("om_reply", id);
        var created = JsonParser.parseString(body("/cardkit/v1/cards").get("data").getAsString()).getAsJsonObject();
        assertFalse(created.has("header"));
        assertTrue(created.getAsJsonObject("config").get("streaming_mode").getAsBoolean());
        assertEquals("Đang trả lời…", created.getAsJsonObject("body").getAsJsonArray("elements").get(0).getAsJsonObject()
                .get("content").getAsString());

        var reply = body("/im/v1/messages/om_question/reply");
        assertEquals("interactive", reply.get("msg_type").getAsString());
        assertFalse(reply.get("reply_in_thread").getAsBoolean());
        assertEquals("card_1", JsonParser.parseString(reply.get("content").getAsString()).getAsJsonObject()
                .getAsJsonObject("data").get("card_id").getAsString());

        var writes = bodies("/cardkit/v1/cards/card_1/elements/answer/content");
        assertTrue(writes.size() >= 2, "the answer is written while it streams and once at the end");
        assertEquals("Bạn có 12 ngày phép &lt;at id=all>&lt;/at>.", writes.getFirst().get("content").getAsString());
        assertEquals("""
                Bạn có **12 ngày** phép &lt;at id=all>&lt;/at>.

                1. [Quy chế](https://tasco.larksuite.com/wiki/abc)

                Trả lời vào tin này để hỏi tiếp.""", writes.getLast().get("content").getAsString());
        var settings = bodies("/cardkit/v1/cards/card_1/settings");
        var closing = JsonParser.parseString(settings.getLast().get("settings").getAsString()).getAsJsonObject().getAsJsonObject("config");
        assertFalse(closing.get("streaming_mode").getAsBoolean());
        assertEquals("Bạn có 12 ngày phép &lt;at id=all>&lt;/at>.", closing.getAsJsonObject("summary").get("content").getAsString());

        List<Integer> sequences = new ArrayList<>();
        for (var call : calls) if (call.body().has("sequence")) sequences.add(call.body().get("sequence").getAsInt());
        for (int i = 1; i < sequences.size(); i++) assertTrue(sequences.get(i) > sequences.get(i - 1), "sequences rise: " + sequences);
    }

    @Test
    void aFailureKeepsWhatWasWrittenAndAddsTheNotice() throws Exception {
        var card = new LarkReplyCard(client(), QUESTION, "Đang trả lời…");
        card.append("Theo quy chế,");
        card.fail("Trợ lý chưa trả lời được câu này. Hãy thử lại.");

        assertEquals("Theo quy chế,\n\nTrợ lý chưa trả lời được câu này. Hãy thử lại.",
                bodies("/cardkit/v1/cards/card_1/elements/answer/content").getLast().get("content").getAsString());
    }

    @Test
    void aWriteAfterLarkClosedTheStreamReopensItOnce() throws Exception {
        closedWrites.set(1);
        var card = new LarkReplyCard(client(), QUESTION, "Đang trả lời…");
        card.finish(List.of("Câu trả lời."), List.of(), null);

        var writes = bodies("/cardkit/v1/cards/card_1/elements/answer/content");
        assertEquals(2, writes.size());
        var reopened = JsonParser.parseString(bodies("/cardkit/v1/cards/card_1/settings").getFirst().get("settings").getAsString())
                .getAsJsonObject().getAsJsonObject("config");
        assertTrue(reopened.get("streaming_mode").getAsBoolean());
    }

    @Test
    void aCardLarkRefusesIsReportedAndNothingIsSent() throws Exception {
        start(false);
        assertThrows(IllegalStateException.class, () -> new LarkReplyCard(sdk(), QUESTION, "Đang trả lời…"));
        assertTrue(bodies("/im/v1/messages/om_question/reply").isEmpty());
    }

    @Test
    void mentionTagsAreWrittenOutLongTextIsCutAndTheSummaryIsOneLine() {
        assertEquals("ping &lt;at user_id=\"ou_1\">Quân&lt;/at> and &lt;person id=x>", LarkReplyCard.inert(
                "ping <at user_id=\"ou_1\">Quân</at> and <person id=x>"));
        assertEquals("a < b and <attention>", LarkReplyCard.inert("a < b and <attention>"));
        String longText = "x".repeat(LarkReplyCard.LIMIT + 50);
        assertTrue(LarkReplyCard.cut(longText).endsWith(" …"));
        assertEquals(LarkReplyCard.LIMIT + 2, LarkReplyCard.cut(longText).length());
        assertEquals("Tiêu đề quan trọng", LarkReplyCard.summary("## **Tiêu đề** quan trọng\n\nPhần còn lại"));
        assertEquals("Chỉ câu trả lời.", LarkReplyCard.markdown("Chỉ câu trả lời.", List.of(), null));
        assertEquals("Trả lời\n\n1. Sổ tay (2026)", LarkReplyCard.markdown("Trả lời", List.of(new ChannelReply.Source("Sổ tay [2026]", null)), " "));
    }

    private Client client() throws IOException {
        start(true);
        return sdk();
    }

    private Client sdk() {
        transport = new LarkTransport(Duration.ofSeconds(3), "memoryos-test", LarkChannelAdapter.CALL);
        return Client.newBuilder("cli_test", "secret").openBaseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .httpTransport(transport).build();
    }

    /** A Lark that hands out a token, a card and a reply, and records every call with its body. */
    private void start(boolean acceptsCards) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath().replaceFirst("^/open-apis", "");
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject body = raw.isBlank() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
            calls.add(new Call(path, body));
            String answer;
            if (path.startsWith("/auth/v3/tenant_access_token")) {
                answer = "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-test\",\"expire\":7200}";
            } else if (path.equals("/cardkit/v1/cards")) {
                answer = acceptsCards ? "{\"code\":0,\"data\":{\"card_id\":\"card_1\"}}" : "{\"code\":230099,\"msg\":\"refused\"}";
            } else if (path.endsWith("/reply")) {
                answer = "{\"code\":0,\"data\":{\"message_id\":\"om_reply\"}}";
            } else if (path.endsWith("/content") && closedWrites.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                answer = "{\"code\":" + LarkReplyCard.STREAMING_CLOSED + ",\"msg\":\"streaming closed\"}";
            } else {
                answer = "{\"code\":0,\"msg\":\"ok\",\"data\":{}}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    private JsonObject body(String path) {
        return bodies(path).getFirst();
    }

    private List<JsonObject> bodies(String path) {
        synchronized (calls) {
            return calls.stream().filter(call -> call.path().equals(path)).map(Call::body).toList();
        }
    }

    private record Call(String path, JsonObject body) {}
}
