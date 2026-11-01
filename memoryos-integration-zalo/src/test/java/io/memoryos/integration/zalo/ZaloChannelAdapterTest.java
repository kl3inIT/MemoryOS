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
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
                Instant.EPOCH, null, "Bot MemoryOS", "1234567890", List.of(), false);
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
        var methods = calls.stream().map(Call::method).toList();
        assertTrue(methods.indexOf("deleteWebhook") >= 0 && methods.indexOf("deleteWebhook") < methods.indexOf("getUpdates"),
                "Zalo serves no poll while a webhook is set: " + methods);
        assertEquals("30", calls("getUpdates").getFirst().body().path("timeout").asString(""));
        assertTrue(transport.connected());
        assertTrue(transport.webhook().isEmpty());
        transport.close();
    }

    @Test
    void everyGroupMessageAddressesTheBotSinceZaloPushesOnlyTheOnesThatNameIt() throws Exception {
        var connection = connection(Map.of("transport", "WEBHOOK"));
        var named = ZaloUpdates.message(connection, "Bot MemoryOS",
                JSON.readTree(update(ZaloUpdates.TEXT, "GROUP", "@Bot MemoryOS hạn nộp báo cáo?", false)));
        assertEquals(ChannelMessage.Kind.GROUP, named.kind());
        assertEquals("g-9", named.chatId());
        assertTrue(named.addressed());
        assertEquals("hạn nộp báo cáo?", named.text());

        var unnamed = ZaloUpdates.message(connection, "Bot MemoryOS",
                JSON.readTree(update(ZaloUpdates.TEXT, "GROUP", "ăn trưa không", false)));
        assertTrue(unnamed.addressed());
        assertEquals("ăn trưa không", unnamed.text());

        assertNull(ZaloUpdates.message(connection, "Bot MemoryOS", JSON.readTree(update(ZaloUpdates.TEXT, "PRIVATE", "hi", true))));
        var image = ZaloUpdates.message(connection, "Bot MemoryOS",
                JSON.readTree(update("message.image.received", "PRIVATE", "", false)));
        assertEquals(ChannelMessage.Type.OTHER, image.type());
    }

    @Test
    void anAnswerIsSentInPiecesWithItsSourcesAndTheTypingMarkMeanwhile() throws Exception {
        var connection = connection(Map.of("transport", "POLLING"));
        answer("getUpdates", 408, "{\"ok\":false,\"error_code\":408}");
        answer("sendMessage", 200, "{\"ok\":true,\"result\":{\"message_id\":\"m-1\",\"date\":1}}");
        var transport = adapter.open(connection, TOKEN, _ -> { });
        var question = ZaloUpdates.message(connection, "Bot MemoryOS",
                JSON.readTree(update(ZaloUpdates.TEXT, "GROUP", "@Bot MemoryOS hỏi", false)));

        var reply = adapter.reply(connection, question, "…");
        waitFor(() -> !calls("sendChatAction").isEmpty());
        String id = reply.finish(List.of("Phần một.", "Phần hai."),
                List.of(new ChannelReply.Source("Quy trình [A]", "https://memoryos.example/d/1")),
                "[Tiếp tục trên MemoryOS](https://memoryos.example/chat/s-1)");

        assertEquals("m-1", id);
        var sent = calls("sendMessage").stream().map(call -> call.body()).toList();
        assertEquals(1, sent.size(), "an answer that fits is one message");
        // Sources and the closing link are bare addresses Zalo makes tappable, never a [text](url) it shows as typed.
        assertEquals("Hà: Phần một.\n\nPhần hai.\n\n1. Quy trình [A]: https://memoryos.example/d/1\n\n"
                + "Tiếp tục trên MemoryOS (https://memoryos.example/chat/s-1)", sent.get(0).path("text").asString(""));
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
        var question = ZaloUpdates.message(connection, "Bot MemoryOS",
                JSON.readTree(update(ZaloUpdates.TEXT, "PRIVATE", "hỏi", false)));

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

    /** The payloads a real bot received (MEM-263 step 1), ids as Zalo sent them. */
    @Test
    void whatARealBotReceivedIsReadAsZaloSentIt() throws Exception {
        var connection = connection(Map.of("transport", "POLLING"));
        var group = ZaloUpdates.message(connection, "Bot MemoryOS", JSON.readTree("""
                {"message":{"chat":{"id":"zgr-c1062b234043a91df052","chat_type":"GROUP"},"text":"@Bot MemoryOS alo",
                 "message_id":"958e8c94ae40d9198056","date":1792058524294,
                 "from":{"id":"fca529c49b8c72d22b9d","is_bot":false,"display_name":"Hồng Đạt"}},
                 "event_name":"message.text.received"}"""));
        assertEquals(ChannelMessage.Kind.GROUP, group.kind());
        assertEquals("zgr-c1062b234043a91df052", group.chatId());
        assertEquals("alo", group.text());
        assertEquals("Hồng Đạt", group.senderName());

        var direct = ZaloUpdates.message(connection, "Bot MemoryOS", JSON.readTree("""
                {"message":{"chat":{"id":"fca529c49b8c72d22b9d","chat_type":"PRIVATE"},"text":"xin chào",
                 "message_id":"d1497becba3bcd62942d","date":1792058261362,
                 "from":{"id":"fca529c49b8c72d22b9d","is_bot":false,"display_name":"Hồng Đạt"}},
                 "event_name":"message.text.received"}"""));
        assertEquals(ChannelMessage.Kind.DIRECT, direct.kind());
        assertEquals("xin chào", direct.text());

        var sticker = ZaloUpdates.message(connection, "Bot MemoryOS", JSON.readTree("""
                {"message":{"chat":{"id":"fca529c49b8c72d22b9d","chat_type":"PRIVATE"},"message_id":"7ab95c168fc1f898a1d7",
                 "date":1792058274164,"message_type":"CHAT_STICKER",
                 "from":{"id":"fca529c49b8c72d22b9d","is_bot":false,"display_name":"Hồng Đạt"},"sticker":"90a800f03cb5d5eb8ca4",
                 "url":"https://zalo-api.zadn.vn/api/emoticon/oasticker?eid=1&size=130"},"event_name":"message.sticker.received"}"""));
        assertEquals(ChannelMessage.Type.OTHER, sticker.type());

        String photo = """
                {"message":{"chat":{"id":"fca529c49b8c72d22b9d","chat_type":"PRIVATE"},"message_id":"34b9eeb7306047391e76",
                 "date":1792058284018,"message_type":"CHAT_PHOTO","from":{"id":"fca529c49b8c72d22b9d","is_bot":false,
                 "display_name":"Hồng Đạt"},"photo_url":"https://photo-stal-27.zdn.vn/no/jpg/x.jpg","caption":"%s"},
                 "event_name":"message.image.received"}""";
        assertEquals(ChannelMessage.Type.OTHER,
                ZaloUpdates.message(connection, "Bot MemoryOS", JSON.readTree(photo.formatted(""))).type());
        var captioned = ZaloUpdates.message(connection, "Bot MemoryOS", JSON.readTree(photo.formatted("hoá đơn này của ai?")));
        assertEquals(ChannelMessage.Type.TEXT, captioned.type());
        assertEquals("hoá đơn này của ai?\n\n" + ZaloUpdates.IMAGE_NOT_READ, captioned.text());
    }

    @Test
    void aPollAnswersOneUpdateAsAnObject() throws InterruptedException {
        answer("getUpdates", 200, "{\"ok\":true,\"result\":" + update(ZaloUpdates.TEXT, "GROUP", "@Bot MemoryOS một", false)
                + ",\"error_code\":0}");
        answer("getUpdates", 408, "{\"ok\":false,\"description\":\"Request timeout\",\"error_code\":408}");
        answer("deleteWebhook", 400, "{\"ok\":false,\"description\":\"Bad request\",\"error_code\":400}");
        BlockingQueue<ChannelMessage> received = new LinkedBlockingQueue<>();

        var transport = adapter.open(connection(Map.of("transport", "POLLING")), TOKEN, received::add);

        var message = received.poll(10, TimeUnit.SECONDS);
        assertEquals("một", message == null ? null : message.text(), "no webhook to delete is no failure");
        assertTrue(transport.connected());
        transport.close();
    }

    @Test
    void theMentionIsTheBotsWholeDisplayNameReadAgainAtEachOpening() throws Exception {
        assertEquals("hỏi gì", ZaloUpdates.withoutMention("@bot memoryos hỏi gì", "@Bot MemoryOS"));
        assertEquals("cho tôi hỏi nhé", ZaloUpdates.withoutMention("cho tôi @Bot MemoryOS hỏi nhé", "@Bot MemoryOS"));
        assertEquals("@Bot MemoryOSa hỏi", ZaloUpdates.withoutMention("@Bot MemoryOSa hỏi", "@Bot MemoryOS"));
        assertEquals("", ZaloUpdates.withoutMention("@Bot MemoryOS", "@Bot MemoryOS"));
        assertEquals(", hỏi", ZaloUpdates.withoutMention("@Bot MemoryOS, hỏi", "@Bot MemoryOS"), "punctuation ends the name");
        // A name typed in decomposed form is the same name.
        var decomposed = Normalizer.normalize("@Bot Đạt hỏi", Normalizer.Form.NFD);
        var connection = connection(Map.of("transport", "WEBHOOK"));
        var message = ZaloUpdates.message(connection, "Bot Đạt", JSON.readTree(update(ZaloUpdates.TEXT, "GROUP", decomposed, false)));
        assertEquals("hỏi", message.text());

        answer("getMe", 200, "{\"ok\":true,\"result\":{\"id\":\"1234567890\",\"account_name\":\"bot.iKSmBtrB\",\"display_name\":\"Bot Mới\"}}");
        answer("setWebhook", 200, "{\"ok\":true,\"result\":{\"url\":\"x\",\"verification\":{\"ok\":true}}}");
        var webhook = adapter.open(connection, TOKEN, _ -> { }).webhook().orElseThrow();
        webhook.register();
        String secret = calls("setWebhook").getFirst().body().path("secret_token").asString("");
        var pushed = webhook.accept(Map.of(ZaloChannelAdapter.SECRET_HEADER, secret),
                ("{\"ok\":true,\"result\":" + update(ZaloUpdates.TEXT, "GROUP", "@Bot Mới hạn chót?", false) + "}")
                        .getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertEquals("hạn chót?", pushed.getFirst().text(), "renamed in Bot Creator since the bot was added");
    }

    @Test
    void aSenderWithoutADisplayNameIsKnownByTheirName() throws Exception {
        var message = ZaloUpdates.message(connection(Map.of()), null, JSON.readTree("""
                {"event_name":"message.text.received","message":{"from":{"id":"u-1","name":"Lan"},
                 "chat":{"id":"u-1","chat_type":"PRIVATE"},"text":"hi","message_id":"m-9"}}"""));
        assertEquals("Lan", message.senderName());
    }

    @Test
    void theSecretIsCheckedFromTheHeadersAlone() {
        answer("setWebhook", 200, "{\"ok\":true,\"result\":{\"url\":\"x\",\"verification\":{\"ok\":true}}}");
        var webhook = adapter.open(connection(Map.of("transport", "WEBHOOK")), TOKEN, _ -> { }).webhook().orElseThrow();
        webhook.register();
        String secret = calls("setWebhook").getFirst().body().path("secret_token").asString("");
        assertTrue(webhook.authorized(Map.of(ZaloChannelAdapter.SECRET_HEADER, secret)));
        assertFalse(webhook.authorized(Map.of(ZaloChannelAdapter.SECRET_HEADER, secret.substring(1))));
        assertFalse(webhook.authorized(Map.of()));
    }

    @Test
    void theDaysChecksSpentLeaveTheAddressSetOnlyWhenTheyRanOutOnTheCheck() {
        var connection = connection(Map.of("transport", "WEBHOOK"));
        answer("setWebhook", 200, "{\"ok\":false,\"error_code\":426}");
        var refused = adapter.open(connection, TOKEN, _ -> { }).webhook().orElseThrow();
        assertEquals("CHANNEL_PROVIDER_UNAVAILABLE", assertThrows(ChannelException.class, refused::register).code(),
                "no address was set, so the opening fails and is tried again");

        answers.clear();
        answer("setWebhook", 200, "{\"ok\":true,\"result\":{\"url\":\"x\"}}");
        answer("testWebhook", 200, "{\"ok\":false,\"error_code\":426}");
        var set = adapter.open(connection, TOKEN, _ -> { }).webhook().orElseThrow();
        set.register();
    }

    @Test
    void aWebhookNeedsAnHttpsAddress() {
        webUrl = "http://memoryos.internal";
        assertEquals(Map.of("transport", "POLLING"), adapter.settings(Map.of()));
        assertEquals("CHANNEL_WEB_URL_MISSING", code(() -> adapter.settings(Map.of("transport", "WEBHOOK"))));
        assertEquals("CHANNEL_WEB_URL_MISSING",
                code(() -> adapter.open(connection(Map.of("transport", "WEBHOOK")), TOKEN, _ -> { })));
    }

    @Test
    void anAnswerCutShortKeepsWhatWasSentAndSaysTheRestIsMissing() throws Exception {
        var connection = connection(Map.of("transport", "POLLING"));
        answer("getUpdates", 408, "{\"ok\":false,\"error_code\":408}");
        var transport = adapter.open(connection, TOKEN, _ -> { });
        var question = ZaloUpdates.message(connection, "Bot MemoryOS",
                JSON.readTree(update(ZaloUpdates.TEXT, "PRIVATE", "hỏi", false)));
        answer("sendMessage", 200, "{\"ok\":true,\"result\":{\"message_id\":\"m-1\"}}");
        answer("sendMessage", 500, "{\"ok\":false,\"error_code\":500}");

        String long1 = "Đoạn một. ".repeat(150).strip();
        String long2 = "Đoạn hai. ".repeat(150).strip();
        var incomplete = assertThrows(ChannelReply.Incomplete.class,
                () -> adapter.reply(connection, question, "…").finish(List.of(long1, long2), List.of(), null));
        assertEquals("m-1", incomplete.lastSent());
        transport.close();
    }

    @Test
    void theTypingMarkStopsAtItsLimitWhateverBecomesOfTheAnswer() throws Exception {
        var clock = new Clock() {
            private Instant now = Instant.parse("2026-10-15T10:00:00Z");

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public synchronized Instant instant() {
                Instant current = now;
                now = now.plus(ZaloChannelAdapter.TYPING_AT_MOST.plusSeconds(1));
                return current;
            }
        };
        var bounded = new ZaloChannelAdapter(baseUrl, () -> webUrl, clock);
        var connection = connection(Map.of("transport", "POLLING"));
        answer("getUpdates", 408, "{\"ok\":false,\"error_code\":408}");
        var transport = bounded.open(connection, TOKEN, _ -> { });
        var question = ZaloUpdates.message(connection, "Bot MemoryOS",
                JSON.readTree(update(ZaloUpdates.TEXT, "PRIVATE", "hỏi", false)));

        bounded.reply(connection, question, "…");
        Thread.sleep(ZaloChannelAdapter.TYPING_EVERY.toMillis() + 1_000);

        assertTrue(calls("sendChatAction").size() <= 1, "an answer never finished is not marked past the limit");
        transport.close();
        bounded.close();
    }

    @Test
    void zalosMarkdownGetsTextItShows() {
        var question = new ChannelMessage(UUID.randomUUID(), "m", "u", ChannelMessage.Kind.DIRECT, "u", "Hà", null, true,
                ChannelMessage.Type.TEXT, "q", null);
        var messages = ZaloChannelAdapter.messages(question,
                List.of("## Kết quả\n\nXem [báo cáo](https://memoryos.example/r/(2026)) và `mã`.\n\n| Quý | Doanh thu |\n|---|---|\n| Q1 | 10 |"),
                List.of(), null);
        assertEquals("**Kết quả**\n\nXem báo cáo (https://memoryos.example/r/(2026)) và mã.\n\nQuý: Q1\nDoanh thu: 10",
                messages.getFirst());
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
    }

    @Test
    void theRouteIsTheCapabilitys() {
        assertEquals("/hooks/channels/0af099b0-b70f-455d-af56-a05a53bbc7c5",
                ChannelWebhook.path(UUID.fromString("0af099b0-b70f-455d-af56-a05a53bbc7c5")));
    }
}
