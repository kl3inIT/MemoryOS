package io.memoryos.integration.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.memoryos.channel.ChannelChat;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelGroupPresence;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelReceiver;
import io.memoryos.channel.ChannelReply;
import io.memoryos.shared.ActorId;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The adapter against a stand-in bridge run as a real child process, speaking the bridge's line protocol. */
class WhatsAppChannelAdapterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path directory;
    private Path commands;
    private WhatsAppBridge bridge;
    private WhatsAppChannelAdapter adapter;
    private final Received received = new Received();

    @BeforeEach
    void start() throws IOException, URISyntaxException {
        assumeFalse(File.separatorChar == '\\', "the stand-in bridge is started by a POSIX shell script");
        var source = Path.of(getClass().getResource("/FakeBridge.java").toURI());
        String java = ProcessHandle.current().info().command().orElseThrow();
        var launcher = directory.resolve("bridge");
        Files.writeString(launcher, "#!/bin/sh\nexec '" + java + "' -cp '" + System.getProperty("java.class.path")
                + "' '" + source + "'\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
        commands = directory.resolve("commands.jsonl");
        bridge = new WhatsAppBridge(launcher, "WARN", () -> commands.toString());
        adapter = new WhatsAppChannelAdapter(bridge);
    }

    @AfterEach
    void stop() {
        if (adapter != null) adapter.close();
    }

    private static ChannelConnection bot(String number) {
        return new ChannelConnection(UUID.randomUUID(), ChannelProvider.WHATSAPP, "Trợ lý", number, Map.of(), "vi", true,
                new ActorId(UUID.randomUUID()), 3, Instant.now(), Instant.now(), null, "+" + number, number + "@s.whatsapp.net",
                List.of());
    }

    @Test
    void aBotIsItsNumberInInternationalFormAndTakesNoSecret() {
        var verified = adapter.verify(new ChannelCredentials(" +84 (912) 345-678 ", "", Map.of()));
        assertEquals("84912345678", verified.appId());
        assertEquals("+84912345678", verified.botName());
        for (String wrong : List.of("0912345678", "84 9123", "+8491234567890123", "số")) {
            assertEquals("CHANNEL_INVALID", assertThrows(ChannelException.class,
                    () -> adapter.verify(new ChannelCredentials(wrong, "", Map.of()))).code(), wrong);
        }
        assertEquals("CHANNEL_INVALID", assertThrows(ChannelException.class,
                () -> adapter.verify(new ChannelCredentials("84912345678", "secret", Map.of()))).code());
        assertEquals(Map.of(), adapter.settings(Map.of()));
        assertThrows(ChannelException.class, () -> adapter.settings(Map.of("transport", "POLLING")));
        assertTrue(adapter.features().pairs());
        assertFalse(adapter.features().organisations());
    }

    @Test
    void aNumberWithoutASessionShowsItsCodeUntilItIsScanned() throws IOException {
        var connection = bot("84900000009");
        var transport = adapter.open(connection, "", received);
        await(() -> received.codes.size() == 1);
        assertEquals("2@fake-code", received.codes.getFirst());
        assertTrue(transport.connected());

        emit(connection, "{\"event\":\"paired\",\"jid\":\"84900000009@s.whatsapp.net\"}");
        await(() -> received.paired == 1);
        emit(connection, "{\"event\":\"pairingEnded\",\"reason\":\"TIMEOUT\"}");
        await(() -> !transport.connected());
        assertTrue(transport.stopped().isEmpty(), "unscanned codes are tried again at the next pass");
        assertEquals("84900000009", last("open").path("phone").asString());
    }

    @Test
    void aMessageIsHandedOverAndOnlyThenAcknowledged() throws IOException {
        var connection = bot("84900000001");
        adapter.open(connection, "", received);
        emit(connection, """
                {"event":"message","message":{"id":"M1","chat":"222@lid","group":false,"sender":"222@lid",
                 "senderName":"Lan","type":"TEXT","text":"Quy trình nghỉ phép?","addressed":true,"repliesTo":"Q0"}}""");
        await(() -> received.messages.size() == 1);
        var message = received.messages.getFirst();
        assertEquals(new ChannelMessage(connection.id(), "M1", "222@lid", ChannelMessage.Kind.DIRECT, "222@lid", "Lan",
                null, true, ChannelMessage.Type.TEXT, "Quy trình nghỉ phép?", "Q0"), message);
        await(() -> !ops("ack").isEmpty());

        emit(connection, """
                {"event":"message","message":{"id":"M2","chat":"120363@g.us","group":true,"sender":"222@lid",
                 "type":"TEXT","text":"Đây là hoá đơn","media":true,"addressed":false}}""");
        await(() -> received.messages.size() == 2);
        var caption = received.messages.get(1);
        assertEquals(ChannelMessage.Kind.GROUP, caption.kind());
        assertFalse(caption.addressed());
        assertEquals("Đây là hoá đơn\n\n" + WhatsAppChannelAdapter.MEDIA_NOT_READ, caption.text());
        assertNull(caption.senderName());

        emit(connection, """
                {"event":"message","message":{"id":"M3","chat":"222@lid","sender":"222@lid","type":"OTHER","addressed":true}}""");
        await(() -> received.messages.size() == 3);
        assertEquals(ChannelMessage.Type.OTHER, received.messages.get(2).type());
        assertEquals("", received.messages.get(2).text());
    }

    @Test
    void anAnswerIsSentInPiecesQuotingTheQuestionWithTheTypingMarkMeanwhile() throws IOException {
        var connection = bot("84900000001");
        adapter.open(connection, "", received);
        var question = new ChannelMessage(connection.id(), "M1", "222@lid", ChannelMessage.Kind.DIRECT, "222@lid", "Lan",
                null, true, ChannelMessage.Type.TEXT, "Quy trình?", null);

        var reply = adapter.reply(connection, question, "…");
        await(() -> ops("typing").stream().anyMatch(typing -> typing.path("on").asBoolean()));
        String id = reply.finish(List.of("**Bước 1**: nộp đơn.", "x".repeat(4_060)),
                List.of(new ChannelReply.Source("Quy chế nhân sự", "https://memoryos.example/doc")), "Trả lời bởi MemoryOS");

        var sent = ops("send");
        assertEquals(2, sent.size());
        assertEquals("S" + sent.get(1).path("id").asLong(), id, "the last message's id is the answer's");
        assertTrue(sent.getFirst().path("text").asString().startsWith("*Bước 1*: nộp đơn."));
        assertEquals("M1", sent.getFirst().path("quote").path("id").asString(), "the first piece quotes the question");
        assertTrue(sent.get(1).path("quote").isMissingNode(), "the others do not");
        assertTrue(sent.get(1).path("text").asString().endsWith("1. Quy chế nhân sự: https://memoryos.example/doc\n\nTrả lời bởi MemoryOS"));
        assertTrue(sent.stream().allMatch(piece -> piece.path("text").asString().length() <= WhatsAppChannelAdapter.MAX_TEXT));
        assertFalse(ops("typing").getLast().path("on").asBoolean(), "the mark ends with the answer");
        assertEquals(List.of("M1"), JSON.convertValue(ops("read").getFirst().path("ids"), List.class));
    }

    @Test
    void aSessionWhatsAppEndedStopsTheBotUntilItChanges() throws IOException {
        var connection = bot("84900000001");
        var transport = adapter.open(connection, "", received);
        emit(connection, "{\"event\":\"loggedOut\",\"reason\":\"401\"}");
        await(() -> !transport.connected());
        assertEquals("CHANNEL_WHATSAPP_LOGGED_OUT", transport.stopped().orElseThrow().code());

        var other = bot("84900000002");
        var second = adapter.open(other, "", received);
        emit(other, "{\"event\":\"wrongNumber\",\"jid\":\"84911111111@s.whatsapp.net\"}");
        await(() -> !second.connected());
        assertEquals("CHANNEL_WHATSAPP_WRONG_NUMBER", second.stopped().orElseThrow().code());

        var third = bot("84900000003");
        var taken = adapter.open(third, "", received);
        emit(third, "{\"event\":\"numberInUse\"}");
        await(() -> !taken.connected());
        assertEquals("CHANNEL_WHATSAPP_NUMBER_IN_USE", taken.stopped().orElseThrow().code());
    }

    @Test
    void aBridgeThatEndsClosesItsBotsAndTheNextOpeningStartsAnother() throws IOException {
        var connection = bot("84900000001");
        var transport = adapter.open(connection, "", received);
        try {
            bridge.call("exit", Map.of(), Duration.ofSeconds(2));
        } catch (WhatsAppBridge.Failure ended) {
            // It ends instead of answering.
        }
        await(() -> !transport.connected());
        assertTrue(transport.stopped().isEmpty());

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        ChannelException refused = null;
        while (System.nanoTime() < deadline) {
            try {
                assertTrue(adapter.open(connection, "", received).connected());
                refused = null;
                break;
            } catch (ChannelException backingOff) {
                refused = backingOff;
                sleep(200);
            }
        }
        assertNull(refused, "the bridge is started again after its back-off");
    }

    @Test
    void groupsAreListedAndAGroupCarriesEveryoneInItByLidAndNumber() {
        var connection = bot("84900000001");
        adapter.open(connection, "", received);
        assertEquals(Optional.of(List.of(new ChannelGroupPresence("120363000000000001@g.us", "Phòng Kế toán"),
                new ChannelGroupPresence("120363000000000002@g.us", null))), adapter.groups(connection));
        assertEquals(new ChannelChat(false, "Phòng Kế toán", List.of(
                        new ChannelChat.Person(List.of("222222222222222@lid", "84911111111@s.whatsapp.net")),
                        new ChannelChat.Person(List.of("84922222222@s.whatsapp.net")))),
                adapter.chat(connection, "120363000000000001@g.us"));
        // A group that cannot be read, or someone in it WhatsApp names by nothing, counts as having outsiders.
        assertTrue(adapter.chat(connection, "404@g.us").external());
        assertTrue(adapter.chat(connection, "nameless@g.us").external());
    }

    @Test
    void closingAndRemovingABotReachTheBridge() throws IOException {
        var connection = bot("84900000001");
        var transport = adapter.open(connection, "", received);
        transport.close();
        assertFalse(transport.connected());
        assertEquals(connection.id().toString(), last("close").path("bot").asString());
        adapter.forget(connection);
        assertEquals(connection.id().toString(), last("logout").path("bot").asString(), "only this bot's device");
    }

    private void emit(ChannelConnection connection, String event) throws IOException {
        var node = (ObjectNode) JSON.readTree(event);
        node.put("bot", connection.id().toString());
        bridge.call("emit", Map.of("event", node), Duration.ofSeconds(5));
    }

    private List<JsonNode> ops(String op) {
        try {
            if (!Files.exists(commands)) return List.of();
            var found = new ArrayList<JsonNode>();
            for (String line : Files.readAllLines(commands)) {
                var command = JSON.readTree(line);
                if (op.equals(command.path("op").asString())) found.add(command);
            }
            return found;
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private JsonNode last(String op) {
        await(() -> !ops(op).isEmpty());
        return ops(op).getLast();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in time");
            sleep(20);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class Received implements ChannelReceiver {
        final List<ChannelMessage> messages = new CopyOnWriteArrayList<>();
        final List<String> codes = new CopyOnWriteArrayList<>();
        volatile int paired;

        @Override public void accept(ChannelMessage message) { messages.add(message); }

        @Override public void pairing(String code, Instant expiresAt) { codes.add(code); }

        @Override public void paired() { paired++; }

        @Override public void joined(String chatId, @Nullable String name) {}
    }
}
