package io.memoryos.integration.zalo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelReply;
import io.memoryos.channel.ChannelTransport;
import io.memoryos.channel.ChannelWebhook;
import io.memoryos.shared.ActorId;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Zalo bot against a local stand-in for the Zalo Bot Platform, which answers each call as a test scripts it. */
class ZaloChannelAdapterTest {
    private static final String TOKEN = "1234567890:secret-part_of-the-token";
    private static final String WEB = "https://memoryos.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private String baseUrl;
    /** What each method answers, as status and body, by its name; a list answers in turn and then repeats its last. */
    private final Map<String, List<Answer>> answers = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private String webUrl = WEB;
    private ZaloChannelAdapter adapter;

    private record Answer(int status, String body) {}

    private record Call(String method, String path, JsonNode body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = path.substring(path.lastIndexOf('/') + 1);
            byte[] request = exchange.getRequestBody().readAllBytes();
            calls.add(new Call(method, path, request.length == 0 ? JSON.createObjectNode() : JSON.readTree(request)));
            var scripted = answers.get(method);
            Answer answer;
            if (scripted == null || scripted.isEmpty()) {
                answer = new Answer(200, "{\"ok\":true,\"result\":{}}");
            } else {
                answer = scripted.size() > 1 ? scripted.removeFirst() : scripted.getFirst();
            }
            if (answer.status() == 408) {
                // Zalo holds an empty poll for its whole wait; a short one keeps the loop from spinning here.
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new ZaloChannelAdapter(baseUrl, () -> webUrl);
    }

    @AfterEach
    void stop() {
        adapter.close();
        server.stop(0);
    }

    private void answer(String method, int status, String body) {
        answers.computeIfAbsent(method, _ -> new CopyOnWriteArrayList<>()).add(new Answer(status, body));
    }

    private List<Call> calls(String method) {
        return calls.stream().filter(call -> call.method().equals(method)).toList();
    }

    private static ChannelConnection connection(Map<String, String> settings) {
        return new ChannelConnection(UUID.fromString("0af099b0-b70f-455d-af56-a05a53bbc7c5"), ChannelProvider.ZALO_BOT,
                "Bot MemoryOS", "1234567890", settings, "vi", true, new ActorId(UUID.randomUUID()), 3, Instant.EPOCH,
                Instant.EPOCH, null, "Bot MemoryOS", "1234567890", List.of());
    }

    private static String code(Runnable action) {
        return assertThrows(ChannelException.class, action::run).code();
    }

    private static String update(String eventName, String chatType, String text, boolean fromBot) {
        return """
                {"event_name":"%s","message":{"from":{"id":"u-77","display_name":"Hà","is_bot":%s},
                 "chat":{"id":"%s","chat_type":"%s"},"text":"%s","message_id":"a1b2c3","date":1760500000000}}
                """.formatted(eventName, fromBot, chatType.equals("GROUP") ? "g-9" : "u-77", chatType, text);
    }

    @Test
    void aBotIsItsTokenAndZaloNamesIt() {
        answer("getMe", 200, "{\"ok\":true,\"result\":{\"id\":\"1234567890\",\"account_name\":\"Bot MemoryOS\",\"account_type\":\"BASIC\"}}");

        var verification = adapter.verify(new ChannelCredentials(null, TOKEN, Map.of()));

        assertEquals("1234567890", verification.appId());
        assertEquals("Bot MemoryOS", verification.botName());
        assertTrue(verification.missingScopes().isEmpty());
        assertEquals("/bot" + TOKEN + "/getMe", calls("getMe").getFirst().path());
    }

    @Test
    void aTokenOfAnotherShapeOrOneZaloRefusesIsNotABot() {
        assertEquals("CHANNEL_INVALID", code(() -> adapter.verify(new ChannelCredentials(null, "cli_a1b2c3d4e5f6", Map.of()))));
        assertEquals("CHANNEL_INVALID", code(() -> adapter.verify(new ChannelCredentials("999", TOKEN, Map.of()))));
        assertTrue(calls.isEmpty(), "a token of the wrong shape is never sent");

        answer("getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        assertEquals("CHANNEL_APP_REJECTED", code(() -> adapter.verify(new ChannelCredentials(null, TOKEN, Map.of()))));

        answers.clear();
        answer("getMe", 200, "{\"ok\":false,\"error_code\":401}");
        assertEquals("CHANNEL_APP_REJECTED", code(() -> adapter.verify(new ChannelCredentials(null, TOKEN, Map.of()))));

        server.stop(0);
        assertEquals("CHANNEL_PROVIDER_UNAVAILABLE", code(() -> adapter.verify(new ChannelCredentials(null, TOKEN, Map.of()))));
    }

    @Test
    void aFailedCallNamesItsCodeAndNeverTheToken() {
        server.stop(0);
        var failure = assertThrows(ZaloApiException.class, () -> new ZaloBot(baseUrl, TOKEN).me());
        assertEquals(ZaloApiException.NO_ANSWER, failure.code());
        assertFalse(String.valueOf(failure.getMessage()).contains("secret-part"));
        assertNull(failure.getCause());
    }

    @Test
    void aBotReceivesByWebhookWhereTheDeploymentHasAnAddressElseByPolling() {
        assertEquals(Map.of("transport", "WEBHOOK"), adapter.settings(Map.of()));
        assertEquals(Map.of("transport", "POLLING"), adapter.settings(Map.of("transport", "POLLING")));
        assertEquals("CHANNEL_INVALID", code(() -> adapter.settings(Map.of("transport", "CARRIER_PIGEON"))));
        assertEquals("CHANNEL_INVALID", code(() -> adapter.settings(Map.of("region", "LARK"))));

        webUrl = "";
        assertEquals(Map.of("transport", "POLLING"), adapter.settings(Map.of()));
        assertEquals("CHANNEL_WEB_URL_MISSING", code(() -> adapter.settings(Map.of("transport", "WEBHOOK"))));
        assertEquals("CHANNEL_WEB_URL_MISSING",
                code(() -> adapter.open(connection(Map.of("transport", "WEBHOOK")), TOKEN, _ -> { })));
    }

    @Test
    void aWebhookIsGivenItsAddressAndASecretOfItsOpeningAndChecksIt() {
        answer("setWebhook", 200, """
                {"ok":true,"result":{"url":"x","verification":{"ok":true,"url":"x","outcome":"webhook.ok"}}}""");
        var transport = adapter.open(connection(Map.of("transport", "WEBHOOK")), TOKEN, _ -> { });
        var webhook = transport.webhook().orElseThrow();
        assertTrue(calls("setWebhook").isEmpty(), "the address is given only once the capability serves it");

        webhook.register();

        var set = calls("setWebhook").getFirst().body();
        assertEquals(WEB + "/hooks/channels/0af099b0-b70f-455d-af56-a05a53bbc7c5", set.path("url").asString(""));
        String secret = set.path("secret_token").asString("");
        assertTrue(secret.length() >= 32 && secret.length() <= 256, secret);

        String body = update(ZaloUpdates.TEXT, "PRIVATE", "Chào bot", false);
        var envelope = "{\"ok\":true,\"result\":" + body + "}";
        var accepted = webhook.accept(Map.of(ZaloChannelAdapter.SECRET_HEADER, secret), envelope.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, accepted.orElseThrow().size());
        var message = accepted.orElseThrow().getFirst();
        assertEquals("a1b2c3", message.messageId());
        assertEquals("u-77", message.senderId());
        assertEquals(ChannelMessage.Kind.DIRECT, message.kind());
        assertTrue(message.addressed());
        assertEquals("Chào bot", message.text());
        assertNull(message.organisations());

        assertTrue(webhook.accept(Map.of(ZaloChannelAdapter.SECRET_HEADER, secret + "x"), envelope.getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(webhook.accept(Map.of(), envelope.getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertEquals(List.of(), webhook.accept(Map.of(ZaloChannelAdapter.SECRET_HEADER, secret), "not json".getBytes(StandardCharsets.UTF_8))
                .orElseThrow(), "Zalo's own request that is no message is answered, not refused");

        transport.close();
        assertEquals(1, calls("deleteWebhook").size());
    }

    @Test
    void aWebhookZaloCannotReachIsReportedWithZalosOutcome() {
        answer("setWebhook", 200, """
                {"ok":true,"result":{"url":"x","verification":{"ok":false,"outcome":"webhook.err.dns"}}}""");
        var webhook = adapter.open(connection(Map.of("transport", "WEBHOOK")), TOKEN, _ -> { }).webhook().orElseThrow();
        var failure = assertThrows(ChannelException.class, webhook::register);
        assertEquals("CHANNEL_WEBHOOK_UNREACHABLE", failure.code());
    }

    @Test
    void withoutAVerificationInItsAnswerTheWebhookIsTestedOnce() {
        answer("testWebhook", 200, "{\"ok\":true,\"result\":{\"ok\":false,\"outcome\":\"webhook.http.404\"}}");
        var webhook = adapter.open(connection(Map.of("transport", "WEBHOOK")), TOKEN, _ -> { }).webhook().orElseThrow();
        assertEquals("CHANNEL_WEBHOOK_UNREACHABLE", assertThrows(ChannelException.class, webhook::register).code());
        assertEquals(1, calls("testWebhook").size());
    }

    @Test
    void aPollingBotDropsAnyWebhookAndHandsOverWhatZaloHas() throws InterruptedException {
        answer("getUpdates", 200, "{\"ok\":true,\"result\":" + update(ZaloUpdates.TEXT, "PRIVATE", "Xin chào", false) + "}");
        answer("getUpdates", 408, "{\"ok\":false,\"error_code\":408}");
        BlockingQueue<ChannelMessage> received = new LinkedBlockingQueue<>();

        ChannelTransport transport = adapter.open(connection(Map.of("transport", "POLLING")), TOKEN, received::add);

        var message = received.poll(10, TimeUnit.SECONDS);
        assertEquals("Xin chào", message == null ? null : message.text());
        assertEquals("deleteWebhook", calls.getFirst().method(), "Zalo serves no poll while a webhook is set");
        assertEquals("30", calls("getUpdates").getFirst().body().path("timeout").asString(""));
        assertTrue(transport.connected());
        assertTrue(transport.webhook().isEmpty());
        transport.close();
    }

    @Test
    void aGroupMessageAddressesTheBotOnlyWhenItNamesIt() throws Exception {
        var connection = connection(Map.of("transport", "WEBHOOK"));
        var named = ZaloUpdates.message(connection, JSON.readTree(update(ZaloUpdates.TEXT, "GROUP", "@Bot MemoryOS hạn nộp báo cáo?", false)));
        assertEquals(ChannelMessage.Kind.GROUP, named.kind());
        assertEquals("g-9", named.chatId());
        assertTrue(named.addressed());
        assertEquals("hạn nộp báo cáo?", named.text());

        var unnamed = ZaloUpdates.message(connection, JSON.readTree(update(ZaloUpdates.TEXT, "GROUP", "ăn trưa không", false)));
        assertFalse(unnamed.addressed());

        assertNull(ZaloUpdates.message(connection, JSON.readTree(update(ZaloUpdates.TEXT, "PRIVATE", "hi", true))));
        var image = ZaloUpdates.message(connection, JSON.readTree(update("message.image.received", "PRIVATE", "", false)));
        assertEquals(ChannelMessage.Type.OTHER, image.type());
    }

    @Test
    void anAnswerIsSentInPiecesWithItsSourcesAndTheTypingMarkMeanwhile() throws Exception {
        var connection = connection(Map.of("transport", "POLLING"));
        answer("getUpdates", 408, "{\"ok\":false,\"error_code\":408}");
        answer("sendMessage", 200, "{\"ok\":true,\"result\":{\"message_id\":\"m-1\",\"date\":1}}");
        var transport = adapter.open(connection, TOKEN, _ -> { });
        var question = ZaloUpdates.message(connection, JSON.readTree(update(ZaloUpdates.TEXT, "GROUP", "@Bot MemoryOS hỏi", false)));

        var reply = adapter.reply(connection, question, "…");
        String id = reply.finish(List.of("Phần một.", "Phần hai."),
                List.of(new ChannelReply.Source("Quy trình [A]", "https://memoryos.example/d/1")), "Tiếp tục trên MemoryOS");

        assertEquals("m-1", id);
        var sent = calls("sendMessage").stream().map(call -> call.body()).toList();
        assertEquals(2, sent.size());
        assertEquals("Hà: Phần một.", sent.get(0).path("text").asString(""));
        assertEquals("Phần hai.\n\n1. [Quy trình (A)](https://memoryos.example/d/1)\n\nTiếp tục trên MemoryOS",
                sent.get(1).path("text").asString(""));
        assertEquals("markdown", sent.get(0).path("parse_mode").asString(""));
        assertEquals("g-9", sent.get(0).path("chat_id").asString(""));
        assertFalse(calls("sendChatAction").isEmpty());
        assertEquals("typing", calls("sendChatAction").getFirst().body().path("action").asString(""));
        transport.close();
    }

    @Test
    void aBotOutOfItsPlanSaysSoAndMarkdownZaloRefusesIsSentPlain() throws Exception {
        var connection = connection(Map.of("transport", "POLLING"));
        answer("getUpdates", 408, "{\"ok\":false,\"error_code\":408}");
        var transport = adapter.open(connection, TOKEN, _ -> { });
        var question = ZaloUpdates.message(connection, JSON.readTree(update(ZaloUpdates.TEXT, "PRIVATE", "hỏi", false)));

        answer("sendMessage", 200, "{\"ok\":false,\"error_code\":400}");
        answer("sendMessage", 200, "{\"ok\":true,\"result\":{\"message_id\":\"m-2\"}}");
        assertEquals("m-2", adapter.reply(connection, question, "…").finish(List.of("**đậm"), List.of(), null));
        var sent = calls("sendMessage");
        assertEquals("markdown", sent.get(0).body().path("parse_mode").asString(""));
        assertTrue(sent.get(1).body().path("parse_mode").isMissingNode());

        answers.remove("sendMessage");
        answer("sendMessage", 429, "{\"ok\":false,\"error_code\":429,\"description\":\"Quota exceeded\"}");
        assertEquals("CHANNEL_PROVIDER_QUOTA",
                code(() -> adapter.reply(connection, question, "…").finish(List.of("x"), List.of(), null)));
        transport.close();
    }

    @Test
    void longPiecesAndAGroupNameNeverExceedWhatAMessageTakes() {
        var question = new ChannelMessage(UUID.randomUUID(), "m", "g", ChannelMessage.Kind.GROUP, "u", "Hà", null, true,
                ChannelMessage.Type.TEXT, "q", null);
        String full = "a".repeat(ZaloChannelAdapter.MAX_TEXT);
        var messages = ZaloChannelAdapter.messages(question, List.of(full), List.of(new ChannelReply.Source("Doc", null)), "Cuối");
        assertTrue(messages.stream().allMatch(text -> text.length() <= ZaloChannelAdapter.MAX_TEXT), messages.toString());
        assertTrue(messages.getFirst().startsWith("Hà: a"), "the name stays with the answer it introduces");
        assertTrue(messages.getLast().endsWith("\n\n1. Doc\n\nCuối"), messages.getLast());
        assertEquals(ZaloChannelAdapter.MAX_TEXT, messages.stream().mapToLong(text -> text.chars().filter(c -> c == 'a').count()).sum());
    }

    @Test
    void theRouteIsTheCapabilitys() {
        assertEquals("/hooks/channels/0af099b0-b70f-455d-af56-a05a53bbc7c5",
                ChannelWebhook.path(UUID.fromString("0af099b0-b70f-455d-af56-a05a53bbc7c5")));
    }
}
