package io.memoryos.integration.whatsapp;

import io.memoryos.channel.ChannelAdapter;
import io.memoryos.channel.ChannelChat;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelFeatures;
import io.memoryos.channel.ChannelGroupPresence;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelReceiver;
import io.memoryos.channel.ChannelReply;
import io.memoryos.channel.ChannelSender;
import io.memoryos.channel.ChannelText;
import io.memoryos.channel.ChannelTransport;
import io.memoryos.channel.ChannelVerification;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The WhatsApp bot (MEM-283): a number linked as a companion device through the whatsmeow bridge. WhatsApp names no
 * organisation and no e-mail, so members link their account with a code; it cannot be trusted to rewrite a message,
 * so an answer is sent once written, in pieces of 4,096 characters as GoClaw and Hermes cut them, quoting the
 * question, with the typing mark meanwhile and a pause between pieces. A bot has no secret: it opens into pairing until
 * its own number scans the code, and the session is the bridge's.
 */
public final class WhatsAppChannelAdapter implements ChannelAdapter, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppChannelAdapter.class);
    static final int MAX_TEXT = 4_096;
    private static final ChannelFeatures FEATURES = new ChannelFeatures(false, false, MAX_TEXT, true);
    /** A number in international form, written with or without its plus, spaces, dots, dashes or brackets. */
    private static final Pattern NUMBER = Pattern.compile("^\\+?[1-9][0-9]{7,14}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.()\\-]");
    /** What the model is told after a caption, since only the caption reached it. */
    static final String MEDIA_NOT_READ = "(A file was attached to this message; the assistant cannot read files.)";
    /** How often the typing mark is sent again; WhatsApp drops it after about ten seconds, GoClaw sends it every 8. */
    static final Duration TYPING_EVERY = Duration.ofSeconds(8);
    static final Duration TYPING_AT_MOST = Duration.ofSeconds(90);
    private static final Duration CALL = Duration.ofSeconds(25);
    private static final Duration OPEN = Duration.ofSeconds(50);

    private final WhatsAppBridge bridge;
    /** The open bots, by connection: where the bridge's events of each go. */
    private final Map<UUID, Transport> open = new ConcurrentHashMap<>();

    WhatsAppChannelAdapter(WhatsAppBridge bridge) {
        this.bridge = bridge;
        bridge.listen(new WhatsAppBridge.Listener() {
            @Override
            public void event(JsonNode event) {
                route(event);
            }

            @Override
            public void exited() {
                open.values().forEach(Transport::lost);
            }
        });
    }

    @Override
    public ChannelProvider provider() {
        return ChannelProvider.WHATSAPP;
    }

    @Override
    public ChannelFeatures features() {
        return FEATURES;
    }

    /**
     * A bot is its number, which nothing can be asked about before it is linked; its digits are the app id. It takes no
     * secret.
     */
    @Override
    public ChannelVerification verify(ChannelCredentials credentials) {
        if (!credentials.secret().isEmpty()) throw ChannelException.invalid("A WhatsApp bot takes no secret.");
        String number = number(credentials.appId());
        return new ChannelVerification(number, "+" + number, number + "@s.whatsapp.net", Set.of());
    }

    static String number(@Nullable String given) {
        String compact = given == null ? "" : SEPARATORS.matcher(given.strip()).replaceAll("");
        if (!NUMBER.matcher(compact).matches()) {
            throw ChannelException.invalid("Give the bot's number in international form, such as +84 912 345 678.");
        }
        return compact.startsWith("+") ? compact.substring(1) : compact;
    }

    @Override
    public Map<String, String> settings(Map<String, String> requested) {
        if (!requested.isEmpty()) throw ChannelException.invalid("A WhatsApp bot has no settings.");
        return Map.of();
    }

    @Override
    public ChannelTransport open(ChannelConnection connection, String secret, ChannelReceiver receiver) {
        var transport = new Transport(connection, receiver);
        var previous = open.put(connection.id(), transport);
        if (previous != null) previous.lost();
        try {
            bridge.call("open", Map.of("bot", connection.id().toString(), "phone", connection.appId()), OPEN);
            return transport;
        } catch (WhatsAppBridge.Failure failure) {
            open.remove(connection.id(), transport);
            throw failure.code().equals("INVALID") ? ChannelException.invalid("The bot's number is not valid.")
                    : ChannelException.providerUnavailable();
        }
    }

    /** The bridge's events of each bot reach its transport, in the order the bridge wrote them. */
    private void route(JsonNode event) {
        String bot = event.path("bot").asString("");
        Transport transport;
        try {
            transport = open.get(UUID.fromString(bot));
        } catch (IllegalArgumentException notOurs) {
            return;
        }
        if (transport == null) {
            // A message of a bot closed meanwhile: WhatsApp delivers it again to the next opening.
            return;
        }
        transport.handle(event);
    }

    /** The question is marked read, as a person who saw it and is about to answer would. */
    @Override
    public void acknowledge(ChannelConnection connection, ChannelMessage message) {
        Thread.ofVirtual().name("whatsapp-read").start(() -> read(connection, message));
    }

    private void read(ChannelConnection connection, ChannelMessage message) {
        try {
            var fields = new HashMap<String, Object>();
            fields.put("bot", connection.id().toString());
            fields.put("chat", message.chatId());
            fields.put("ids", List.of(message.messageId()));
            if (message.kind() == ChannelMessage.Kind.GROUP) fields.put("sender", message.senderId());
            bridge.call("read", fields, CALL);
        } catch (WhatsAppBridge.Failure unread) {
            LOG.atDebug().addKeyValue("event", "channel.acknowledge.failed").addKeyValue("provider", ChannelProvider.WHATSAPP)
                    .addKeyValue("error_code", unread.code()).log("WhatsApp read receipt was not sent");
        }
    }

    @Override
    public ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting) {
        read(connection, message);
        return new Reply(connection, message);
    }

    @Override
    public void notice(ChannelConnection connection, ChannelMessage message, String text) {
        send(connection, message, text, true);
    }

    @Override
    public ChannelSender sender(ChannelConnection connection, String senderId) {
        return ChannelSender.UNKNOWN;
    }

    /**
     * A group is answered only once the capability can tell that everyone in it belongs to the Tenant (MEM-285), so
     * until then every WhatsApp group counts as having outsiders; its name is WhatsApp's.
     */
    @Override
    public ChannelChat chat(ChannelConnection connection, String chatId) {
        String name = null;
        try {
            var group = bridge.call("group", Map.of("bot", connection.id().toString(), "chat", chatId), CALL);
            String given = group.path("name").asString("");
            if (!given.isBlank()) name = given;
        } catch (WhatsAppBridge.Failure unread) {
            // A group that cannot be read counts as having outsiders, as Lark's does.
        }
        return new ChannelChat(true, name);
    }

    /** The groups the bot is in, the whole list or nothing. */
    @Override
    public Optional<List<ChannelGroupPresence>> groups(ChannelConnection connection) {
        try {
            var listed = bridge.call("groups", Map.of("bot", connection.id().toString()), CALL);
            var groups = new ArrayList<ChannelGroupPresence>();
            for (var group : listed) {
                String name = group.path("name").asString("");
                groups.add(new ChannelGroupPresence(group.path("chat").asString(), name.isBlank() ? null : name));
            }
            return Optional.of(List.copyOf(groups));
        } catch (WhatsAppBridge.Failure unlisted) {
            return Optional.empty();
        }
    }

    /** A removed bot's device is logged out of WhatsApp and its session deleted. */
    @Override
    public void forget(ChannelConnection connection) {
        bridge.call("logout", Map.of("bot", connection.id().toString(), "phone", connection.appId()), OPEN);
    }

    @Override
    public void close() {
        bridge.close();
    }

    /**
     * Sends one message to the question's chat, quoting it when asked; returns WhatsApp's id of the message.
     *
     * @throws ChannelException {@code CHANNEL_PROVIDER_UNAVAILABLE} when it was not sent
     */
    private @Nullable String send(ChannelConnection connection, ChannelMessage question, String text, boolean quote) {
        var fields = new HashMap<String, Object>();
        fields.put("bot", connection.id().toString());
        fields.put("chat", question.chatId());
        fields.put("text", text);
        if (quote && question.type() == ChannelMessage.Type.TEXT) {
            fields.put("quote", Map.of("id", question.messageId(), "sender", question.senderId(),
                    "text", question.text()));
        }
        try {
            String id = bridge.call("send", fields, CALL).path("messageId").asString("");
            return id.isEmpty() ? null : id;
        } catch (WhatsAppBridge.Failure failure) {
            throw ChannelException.providerUnavailable();
        }
    }

    private void typing(ChannelConnection connection, String chatId, boolean on) {
        try {
            bridge.call("typing", Map.of("bot", connection.id().toString(), "chat", chatId, "on", on), CALL);
        } catch (WhatsAppBridge.Failure unmarked) {
            LOG.atDebug().addKeyValue("event", "channel.typing.failed").addKeyValue("provider", ChannelProvider.WHATSAPP)
                    .addKeyValue("error_code", unmarked.code()).log("WhatsApp typing mark was not sent");
        }
    }

    /**
     * The messages an answer is sent as: its pieces joined with the sources, as a numbered list of titles and bare
     * addresses, and the closing line, made WhatsApp's formatting and cut again at its length.
     */
    static List<String> messages(List<String> written, List<ChannelReply.Source> sources, @Nullable String footer) {
        var whole = new StringBuilder(String.join("\n\n", written).strip());
        var tail = new StringBuilder();
        int index = 1;
        for (var source : sources) {
            tail.append(index++).append(". ").append(source.title().strip());
            if (source.url() != null) tail.append(": ").append(source.url());
            tail.append('\n');
        }
        if (footer != null && !footer.isBlank()) tail.append('\n').append(footer.strip());
        String text = WhatsAppText.of(whole.toString());
        if (!tail.isEmpty()) text = (text + "\n\n" + tail.toString().strip()).strip();
        return ChannelText.pieces(text, MAX_TEXT);
    }

    /** An answer being written: the typing mark until it is sent, and never longer than {@link #TYPING_AT_MOST}. */
    private final class Reply implements ChannelReply {
        private final ChannelConnection connection;
        private final ChannelMessage question;
        private final Thread typing;
        private volatile boolean done;

        Reply(ChannelConnection connection, ChannelMessage question) {
            this.connection = connection;
            this.question = question;
            this.typing = Thread.ofVirtual().name("whatsapp-typing").start(this::type);
        }

        private void type() {
            Instant until = Instant.now().plus(TYPING_AT_MOST);
            while (!done && Instant.now().isBefore(until)) {
                typing(connection, question.chatId(), true);
                try {
                    Thread.sleep(TYPING_EVERY);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }

        private void stop() {
            done = true;
            typing.interrupt();
            typing(connection, question.chatId(), false);
        }

        @Override
        public void append(String text) {
            // Not called: WhatsApp's answer is sent once written.
        }

        /** Each piece in turn, the first quoting the question; one that fails after others were sent says so. */
        @Override
        public @Nullable String finish(List<String> pieces, List<Source> sources, @Nullable String footer) {
            stop();
            String last = null;
            boolean sent = false;
            for (var text : messages(pieces, sources, footer)) {
                try {
                    // A random pause between pieces, so a burst of answers does not read as a machine's.
                    if (sent) Thread.sleep(ThreadLocalRandom.current().nextLong(500, 1_500));
                    last = send(connection, question, text, !sent);
                    sent = true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ChannelReply.Incomplete(last);
                } catch (RuntimeException failure) {
                    if (!sent) throw failure;
                    throw new ChannelReply.Incomplete(last);
                }
            }
            return last;
        }

        @Override
        public void fail(String notice) {
            stop();
            send(connection, question, notice, true);
        }
    }

    /**
     * One bot opened in the bridge. It stays connected while pairing or linked, and stops for good, until the bot
     * changes, when WhatsApp logs it out, bans the number, opens the session elsewhere, or another number scans its code.
     */
    private final class Transport implements ChannelTransport {
        private final ChannelConnection connection;
        private final ChannelReceiver receiver;
        private volatile boolean connected = true;
        private volatile @Nullable ChannelException stopped;

        Transport(ChannelConnection connection, ChannelReceiver receiver) {
            this.connection = connection;
            this.receiver = receiver;
        }

        void handle(JsonNode event) {
            switch (event.path("event").asString("")) {
                case "qr" -> receiver.pairing(event.path("code").asString(), instant(event.path("expiresAt")));
                case "paired" -> receiver.paired();
                case "pairingEnded" -> {
                    // The codes ran out unscanned: the next pass opens it again with new ones.
                    receiver.paired();
                    connected = false;
                }
                case "loggedOut" -> stop(ChannelException.whatsappLoggedOut());
                case "replaced" -> stop(ChannelException.whatsappReplaced());
                case "banned" -> stop(ChannelException.whatsappBanned());
                case "wrongNumber" -> {
                    receiver.paired();
                    stop(ChannelException.whatsappWrongNumber());
                }
                case "outdated" -> {
                    LOG.atError().addKeyValue("event", "channel.whatsapp.outdated")
                            .log("WhatsApp refused the bridge's client version; whatsmeow must be updated");
                    connected = false;
                }
                case "joined" -> {
                    String name = event.path("name").asString("");
                    receiver.joined(event.path("chat").asString(), name.isBlank() ? null : name);
                }
                case "message" -> message(event);
                default -> {
                    // connected, disconnected: whatsmeow reconnects by itself.
                }
            }
        }

        /** Handed to the capability, which claims it, and only then acknowledged to WhatsApp. */
        private void message(JsonNode event) {
            var message = WhatsAppChannelAdapter.message(connection.id(), event.path("message"));
            try {
                if (message != null) receiver.accept(message);
            } finally {
                bridge.ack(event.path("seq").asLong());
            }
        }

        private void stop(ChannelException reason) {
            stopped = reason;
            connected = false;
        }

        /** The bridge ended: the next pass opens the bot again in the next bridge. */
        void lost() {
            connected = false;
        }

        @Override
        public boolean connected() {
            return connected;
        }

        @Override
        public Optional<ChannelException> stopped() {
            return Optional.ofNullable(stopped);
        }

        @Override
        public void close() {
            connected = false;
            if (!open.remove(connection.id(), this)) return;
            try {
                bridge.call("close", Map.of("bot", connection.id().toString()), CALL);
            } catch (WhatsAppBridge.Failure gone) {
                // A bridge that ended holds nothing to close.
            }
        }
    }

    /** A message as the bridge wrote it; null for one without the fields every message has. */
    static @Nullable ChannelMessage message(UUID connection, JsonNode message) {
        String id = message.path("id").asString("");
        String chat = message.path("chat").asString("");
        String sender = message.path("sender").asString("");
        if (id.isEmpty() || chat.isEmpty() || sender.isEmpty()) return null;
        boolean text = "TEXT".equals(message.path("type").asString(""));
        String body = text ? message.path("text").asString("") : "";
        if (text && message.path("media").asBoolean(false)) body = body + "\n\n" + MEDIA_NOT_READ;
        String name = message.path("senderName").asString("");
        String repliesTo = message.path("repliesTo").asString("");
        return new ChannelMessage(connection, id, chat,
                message.path("group").asBoolean(false) ? ChannelMessage.Kind.GROUP : ChannelMessage.Kind.DIRECT, sender,
                name.isBlank() ? null : name, null, message.path("addressed").asBoolean(false),
                text ? ChannelMessage.Type.TEXT : ChannelMessage.Type.OTHER, body, repliesTo.isEmpty() ? null : repliesTo);
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asString(""));
        } catch (DateTimeParseException unreadable) {
            return Instant.now().plusSeconds(20);
        }
    }
}
