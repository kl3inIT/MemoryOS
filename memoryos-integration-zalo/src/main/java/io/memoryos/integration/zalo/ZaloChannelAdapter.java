package io.memoryos.integration.zalo;

import io.memoryos.channel.ChannelAdapter;
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelDelivery;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelFeatures;
import io.memoryos.channel.ChannelMessage;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelReply;
import io.memoryos.channel.ChannelSender;
import io.memoryos.channel.ChannelText;
import io.memoryos.channel.ChannelTransport;
import io.memoryos.channel.ChannelVerification;
import io.memoryos.channel.ChannelWebhook;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Zalo bot (MEM-263) on the Zalo Bot Platform. Zalo names no organisation and no e-mail, cannot edit a sent message
 * and takes 2,000 characters a message. A bot receives as its administrator chose: Zalo pushes to the deployment's
 * public address ({@link ChannelDelivery#WEBHOOK}), checked with a secret made for each opening, or the bot asks Zalo
 * ({@link ChannelDelivery#POLLING}) for a host Zalo cannot call. The answer is sent once it is written, in pieces, with
 * Zalo's typing mark meanwhile; a group answer starts with the asker's name, since Zalo has no quote.
 */
public final class ZaloChannelAdapter implements ChannelAdapter, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ZaloChannelAdapter.class);
    static final int MAX_TEXT = 2_000;
    private static final ChannelFeatures FEATURES = new ChannelFeatures(false, false, false, MAX_TEXT);
    /** A token is the bot's number, a colon and its secret. */
    private static final Pattern TOKEN = Pattern.compile("^([0-9]{1,32}):(\\S{1,200})$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]\\n]+)]\\((https?://[^\\s)]+)\\)");
    static final String SECRET_HEADER = "x-bot-api-secret-token";
    /** How often the typing mark is sent again while an answer is written; Zalo does not say how long one lasts. */
    static final Duration TYPING_EVERY = Duration.ofSeconds(4);
    /** How often a webhook bot asks Zalo whether its address is still the one set. */
    static final Duration WEBHOOK_CHECK_EVERY = Duration.ofMinutes(1);
    private static final Duration POLL_BACKOFF_MAX = Duration.ofMinutes(1);
    /** A poll answered sooner than this with nothing waits this long before the next. */
    private static final Duration QUICK_POLL = Duration.ofSeconds(1);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String baseUrl;
    private final Supplier<String> webUrl;
    private final Clock clock;
    /** The open bots' clients, by connection, for the replies and notices of their messages. */
    private final Map<UUID, ZaloBot> bots = new ConcurrentHashMap<>();

    /**
     * @param baseUrl Zalo's address, {@code https://bot-api.zaloplatforms.com}
     * @param webUrl the deployment's public address, empty when it has none
     */
    public ZaloChannelAdapter(String baseUrl, Supplier<String> webUrl) {
        this(baseUrl, webUrl, Clock.systemUTC());
    }

    ZaloChannelAdapter(String baseUrl, Supplier<String> webUrl, Clock clock) {
        this.baseUrl = baseUrl;
        this.webUrl = webUrl;
        this.clock = clock;
    }

    @Override
    public ChannelProvider provider() {
        return ChannelProvider.ZALO_BOT;
    }

    @Override
    public ChannelFeatures features() {
        return FEATURES;
    }

    /** A bot is its token, from Zalo Bot Creator; its app id is the number before the colon. */
    @Override
    public ChannelVerification verify(ChannelCredentials credentials) {
        var token = TOKEN.matcher(credentials.secret());
        if (!token.matches()) throw ChannelException.invalid("Supply the bot's token from Zalo Bot Creator.");
        String id = token.group(1);
        if (credentials.appId() != null && !credentials.appId().equals(id)) {
            throw ChannelException.invalid("A Zalo bot is known by its token alone.");
        }
        try {
            var me = new ZaloBot(baseUrl, credentials.secret()).me();
            if (!me.id().isEmpty() && !me.id().equals(id)) throw ChannelException.rejected();
            String name = me.name() == null || me.name().isBlank() ? id : me.name();
            return new ChannelVerification(id, name, id, Set.of());
        } catch (ZaloApiException refused) {
            throw refused.rejected() || refused.code() == 404 ? ChannelException.rejected() : ChannelException.providerUnavailable();
        }
    }

    /**
     * A Zalo bot's one setting is how it receives: by webhook, the default, where the deployment has a public address,
     * else by polling.
     */
    @Override
    public Map<String, String> settings(Map<String, String> requested) {
        if (!Set.of(ChannelDelivery.SETTING).containsAll(requested.keySet())) {
            throw ChannelException.invalid("A Zalo bot has no setting but how it receives.");
        }
        ChannelDelivery delivery;
        if (requested.get(ChannelDelivery.SETTING) == null) {
            delivery = webUrl.get().isEmpty() ? ChannelDelivery.POLLING : ChannelDelivery.WEBHOOK;
        } else {
            delivery = ChannelDelivery.of(requested)
                    .orElseThrow(() -> ChannelException.invalid("Choose webhook or polling for the bot."));
        }
        if (delivery == ChannelDelivery.WEBHOOK && webUrl.get().isEmpty()) throw ChannelException.webUrlMissing();
        return Map.of(ChannelDelivery.SETTING, delivery.name());
    }

    @Override
    public ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver) {
        var bot = new ZaloBot(baseUrl, secret);
        var delivery = ChannelDelivery.of(connection.settings()).orElse(ChannelDelivery.POLLING);
        String address = webUrl.get();
        if (delivery == ChannelDelivery.WEBHOOK && address.isEmpty()) throw ChannelException.webUrlMissing();
        // Known before the first message arrives, so its reply finds the bot.
        bots.put(connection.id(), bot);
        try {
            return delivery == ChannelDelivery.WEBHOOK
                    ? new Webhook(connection, bot, address + ChannelWebhook.path(connection.id()))
                    : new Polling(connection, bot, receiver);
        } catch (RuntimeException failure) {
            bots.remove(connection.id(), bot);
            throw failure;
        }
    }

    @Override
    public void acknowledge(ChannelConnection connection, ChannelMessage message) {
        try {
            bot(connection).typing(message.chatId());
        } catch (RuntimeException unmarked) {
            LOG.atDebug().addKeyValue("event", "channel.acknowledge.failed").addKeyValue("provider", ChannelProvider.ZALO_BOT)
                    .addKeyValue("error_type", unmarked.getClass().getName()).log("Zalo typing mark was not sent");
        }
    }

    @Override
    public ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting) {
        return new Reply(bot(connection), message);
    }

    @Override
    public void notice(ChannelConnection connection, ChannelMessage message, String text) {
        send(bot(connection), message.chatId(), text, false);
    }

    @Override
    public ChannelSender sender(ChannelConnection connection, String senderId) {
        return ChannelSender.UNKNOWN;
    }

    @Override
    public boolean externalChat(ChannelConnection connection, String chatId) {
        return false;
    }

    @Override
    public void close() {
        bots.clear();
    }

    private ZaloBot bot(ChannelConnection connection) {
        var bot = bots.get(connection.id());
        if (bot == null) throw ChannelException.providerUnavailable();
        return bot;
    }

    /** Sends one message; markdown Zalo refuses is sent again as plain text. */
    private static @Nullable String send(ZaloBot bot, String chatId, String text, boolean markdown) {
        try {
            return bot.send(chatId, text, markdown);
        } catch (ZaloApiException failure) {
            if (failure.quota()) throw ChannelException.providerQuota();
            if (failure.rejected()) throw ChannelException.rejected();
            if (markdown && failure.code() != ZaloApiException.NO_ANSWER) {
                try {
                    return bot.send(chatId, text, false);
                } catch (ZaloApiException again) {
                    if (again.quota()) throw ChannelException.providerQuota();
                }
            }
            throw ChannelException.providerUnavailable();
        }
    }

    /** The messages an answer is sent as: its pieces, the first after the asker's name in a group, then its sources and closing line. */
    static List<String> messages(ChannelMessage question, List<String> written, List<ChannelReply.Source> sources,
                                 @Nullable String footer) {
        // Zalo's markdown shows a [text](url) link as written, so a link becomes its text and its tappable bare address;
        // the piece only gets shorter.
        var pieces = written.stream().map(piece -> MARKDOWN_LINK.matcher(piece).replaceAll("$1 ($2)")).toList();
        var texts = new ArrayList<String>();
        if (question.kind() == ChannelMessage.Kind.GROUP && question.senderName() != null && !pieces.isEmpty()) {
            // The name goes before the first piece, which is cut again to leave it room.
            String name = question.senderName().strip() + ": ";
            var first = ChannelText.pieces(pieces.getFirst(), Math.max(1, MAX_TEXT - name.length()));
            for (int i = 0; i < first.size(); i++) texts.add(i == 0 ? name + first.get(i) : first.get(i));
            texts.addAll(pieces.subList(1, pieces.size()));
        } else {
            texts.addAll(pieces);
        }
        var tail = new StringBuilder();
        int index = 1;
        for (var source : sources) {
            String title = source.title().strip();
            tail.append(index++).append(". ");
            // Zalo's markdown shows no [text](url) link, only a bare address, which it makes tappable.
            tail.append(title);
            if (source.url() != null) tail.append(": ").append(source.url());
            tail.append('\n');
        }
        if (footer != null && !footer.isBlank()) tail.append('\n').append(footer.strip());
        String closing = tail.toString().strip();
        if (!closing.isEmpty()) {
            if (!texts.isEmpty() && texts.getLast().length() + 2 + closing.length() <= MAX_TEXT) {
                texts.set(texts.size() - 1, texts.getLast() + "\n\n" + closing);
            } else {
                texts.add(closing);
            }
        }
        var out = new ArrayList<String>();
        for (var text : texts) out.addAll(ChannelText.pieces(text, MAX_TEXT));
        return out;
    }

    /** An answer being written: the typing mark until it is sent. */
    private final class Reply implements ChannelReply {
        private final ZaloBot bot;
        private final ChannelMessage question;
        private final Thread typing;
        private volatile boolean done;

        Reply(ZaloBot bot, ChannelMessage question) {
            this.bot = bot;
            this.question = question;
            // The first mark is sent at once, so even a quick answer shows the bot at work.
            mark();
            this.typing = Thread.ofVirtual().name("zalo-typing").start(this::type);
        }

        private void mark() {
            try {
                bot.typing(question.chatId());
            } catch (RuntimeException unmarked) {
                // Best effort: the answer is sent whether or not the mark shows.
            }
        }

        private void type() {
            while (!done) {
                try {
                    Thread.sleep(TYPING_EVERY);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (!done) mark();
            }
        }

        private void stop() {
            done = true;
            typing.interrupt();
        }

        @Override
        public void append(String text) {
            // Zalo cannot rewrite a message, so the capability never streams to it.
        }

        @Override
        public @Nullable String finish(List<String> pieces, List<Source> sources, @Nullable String footer) {
            stop();
            String last = null;
            for (var text : messages(question, pieces, sources, footer)) last = send(bot, question.chatId(), text, true);
            return last;
        }

        @Override
        public void fail(String notice) {
            stop();
            send(bot, question.chatId(), notice, false);
        }
    }

    /** Zalo pushes this bot's messages to the deployment, checked with a secret of this opening. */
    private final class Webhook implements ChannelTransport, ChannelWebhook {
        private final ChannelConnection connection;
        private final ZaloBot bot;
        private final String address;
        private final byte[] secret;
        private final String secretText;
        private volatile boolean registered;
        private volatile boolean current = true;
        private volatile Instant checkedAt;

        Webhook(ChannelConnection connection, ZaloBot bot, String address) {
            this.connection = connection;
            this.bot = bot;
            this.address = address;
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            this.secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            this.secret = secretText.getBytes(StandardCharsets.UTF_8);
            this.checkedAt = clock.instant();
        }

        @Override
        public Optional<ChannelWebhook> webhook() {
            return Optional.of(this);
        }

        @Override
        public void register() {
            ZaloBot.Check check;
            try {
                check = bot.setWebhook(address, secretText);
                registered = true;
                if (check == null) check = bot.testWebhook();
            } catch (ZaloApiException failure) {
                if (failure.checksSpent()) return;
                if (failure.rejected()) throw ChannelException.rejected();
                throw ChannelException.providerUnavailable();
            }
            if (!check.ok()) throw ChannelException.webhookUnreachable(check.outcome() == null ? "unknown" : check.outcome());
            checkedAt = clock.instant();
        }

        /** Checks the secret header before the body is read, in constant time. */
        @Override
        public Optional<List<ChannelMessage>> accept(Map<String, String> headers, byte[] body) {
            String given = headers.get(SECRET_HEADER);
            if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) return Optional.empty();
            try {
                return Optional.of(ZaloUpdates.messages(connection, JSON.readTree(body)));
            } catch (JacksonException unreadable) {
                // Zalo's own, but not a message: its check of the address, or a shape not known here.
                return Optional.of(List.of());
            }
        }

        /** Whether Zalo still pushes here, asked at most once a minute; an unanswered question keeps the last answer. */
        @Override
        public boolean connected() {
            Instant now = clock.instant();
            if (registered && checkedAt.plus(WEBHOOK_CHECK_EVERY).isBefore(now)) {
                checkedAt = now;
                try {
                    current = address.equals(bot.webhookUrl());
                } catch (ZaloApiException unanswered) {
                    current = !unanswered.rejected();
                }
            }
            return current;
        }

        @Override
        public void close() {
            bots.remove(connection.id(), bot);
            if (!registered) return;
            try {
                bot.deleteWebhook();
            } catch (ZaloApiException failure) {
                LOG.atWarn().addKeyValue("event", "channel.connection.close_failed").addKeyValue("provider", ChannelProvider.ZALO_BOT)
                        .addKeyValue("error_code", failure.code()).log("Zalo webhook was not removed");
            }
        }
    }

    /** This bot asks Zalo for its messages, one long poll after another. */
    private final class Polling implements ChannelTransport {
        private final ChannelConnection connection;
        private final ZaloBot bot;
        private final Consumer<ChannelMessage> receiver;
        private final Thread loop;
        private volatile boolean closed;
        private volatile boolean failed;

        Polling(ChannelConnection connection, ZaloBot bot, Consumer<ChannelMessage> receiver) {
            this.connection = connection;
            this.bot = bot;
            this.receiver = receiver;
            try {
                // Zalo serves no poll while a webhook is set.
                bot.deleteWebhook();
            } catch (ZaloApiException failure) {
                if (failure.rejected()) throw ChannelException.rejected();
                throw ChannelException.providerUnavailable();
            }
            this.loop = Thread.ofVirtual().name("zalo-poll").start(this::poll);
        }

        private void poll() {
            Duration backoff = Duration.ofSeconds(1);
            while (!closed) {
                try {
                    long started = System.nanoTime();
                    var updates = bot.poll();
                    for (var update : updates) {
                        var message = ZaloUpdates.message(connection, update);
                        if (message != null) receiver.accept(message);
                    }
                    backoff = Duration.ofSeconds(1);
                    // An empty poll Zalo answered at once instead of holding is not asked again at once.
                    if (!updates.isEmpty() || System.nanoTime() - started >= QUICK_POLL.toNanos()) continue;
                    backoff = QUICK_POLL;
                } catch (ZaloApiException failure) {
                    if (closed) return;
                    if (failure.rejected()) {
                        // The token was reset; the supervisor sees the transport down and opens it again.
                        failed = true;
                        return;
                    }
                    LOG.atWarn().addKeyValue("event", "channel.poll.failed").addKeyValue("provider", ChannelProvider.ZALO_BOT)
                            .addKeyValue("error_code", failure.code()).log("Zalo poll failed");
                } catch (RuntimeException unexpected) {
                    if (closed) return;
                    LOG.atWarn().addKeyValue("event", "channel.poll.failed").addKeyValue("provider", ChannelProvider.ZALO_BOT)
                            .addKeyValue("error_type", unexpected.getClass().getName()).log("Zalo poll failed");
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(POLL_BACKOFF_MAX) > 0 ? POLL_BACKOFF_MAX : backoff.multipliedBy(2);
            }
        }

        @Override
        public boolean connected() {
            return !failed && loop.isAlive();
        }

        @Override
        public void close() {
            closed = true;
            loop.interrupt();
            bots.remove(connection.id(), bot);
        }
    }
}
