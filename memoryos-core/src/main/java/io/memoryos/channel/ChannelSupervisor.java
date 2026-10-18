package io.memoryos.channel;

import io.memoryos.BusinessException;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;

/**
 * Holds open the transport of every bot that is switched on, in the process that receives (MEM-246). Every
 * {@link #PASS} it opens a bot switched on since, closes one switched off or removed, reopens one whose
 * connection changed or dropped, and writes what it saw to {@code channel_connection_state}. A failed open is
 * recorded with its typed code and tried again at the next pass. Each message an adapter hands over is answered on
 * a virtual thread of its own, so the adapter's acknowledgement to the provider never waits for an answer. The group
 * chats a bot is in are read from a provider that lists them when the bot connects and every {@link #LISTING} after
 * (MEM-273), on a virtual thread, so a slow list never holds a pass.
 *
 * <p>It is no component: only the process that receives imports it, so no other process opens a connection.
 */
public class ChannelSupervisor implements SmartLifecycle {
    static final Duration PASS = Duration.ofSeconds(30);
    /** How old an unsettled claim of an earlier process is before it is taken for abandoned. */
    static final Duration ABANDONED = Duration.ofMinutes(10);
    /** How often an open bot's group chats are read again from a provider that lists them. */
    static final Duration LISTING = Duration.ofMinutes(10);
    private static final Logger LOG = LoggerFactory.getLogger(ChannelSupervisor.class);
    private static final String SECRET_PURPOSE = "app-secret";

    private final JdbcChannelConnectionRepository connections;
    private final ChannelCredentialConfiguration credentials;
    private final ChannelAdapters adapters;
    private final ChannelInboundService inbound;
    private final ChannelGroupRoster roster;
    private final Clock clock;
    /**
     * Failures that opening again cannot mend until an administrator changes the bot: the provider could not reach the
     * webhook, or this deployment has no address to give it. They wait for the next revision rather than being tried
     * every pass, which would spend the provider's daily webhook checks.
     */
    private static final Set<String> AWAIT_CHANGE = Set.of("CHANNEL_WEBHOOK_UNREACHABLE", "CHANNEL_WEB_URL_MISSING");

    private final Map<UUID, Held> held = new HashMap<>();
    /** The webhook of each bot whose transport receives over HTTP, read by the route without the pass's lock. */
    private final Map<UUID, Pushed> webhooks = new ConcurrentHashMap<>();
    private final ExecutorService answers = Executors.newVirtualThreadPerTaskExecutor();
    private @Nullable ScheduledExecutorService passes;
    /** When this process began receiving: a claim older than that was another process's, which may have ended. */
    private Instant startedAt = Instant.MIN;

    @Autowired
    public ChannelSupervisor(JdbcChannelConnectionRepository connections, ChannelCredentialConfiguration credentials,
                             ChannelAdapters adapters, ChannelInboundService inbound, ChannelGroupRoster roster) {
        this(connections, credentials, adapters, inbound, roster, Clock.systemUTC());
    }

    ChannelSupervisor(JdbcChannelConnectionRepository connections, ChannelCredentialConfiguration credentials,
                      ChannelAdapters adapters, ChannelInboundService inbound, ChannelGroupRoster roster, Clock clock) {
        this.connections = connections;
        this.credentials = credentials;
        this.adapters = adapters;
        this.inbound = inbound;
        this.roster = roster;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (passes != null) return;
        startedAt = clock.instant();
        passes = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("channel-supervisor").factory());
        passes.scheduleWithFixedDelay(this::pass, 0, PASS.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Closes every connection first, then lets the answers in progress end. */
    @Override
    public synchronized void stop() {
        if (passes == null) return;
        passes.shutdownNow();
        passes = null;
        held.values().forEach(Held::close);
        held.clear();
        answers.shutdown();
        try {
            if (!answers.awaitTermination(30, TimeUnit.SECONDS)) answers.shutdownNow();
        } catch (InterruptedException interrupted) {
            answers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return passes != null;
    }

    /** One reconciliation of what is open with what is switched on. */
    synchronized void pass() {
        try {
            Instant now = clock.instant();
            sweep(now);
            var wanted = new HashSet<UUID>();
            for (var enabled : connections.listEnabled()) {
                var connection = enabled.connection();
                wanted.add(connection.id());
                var current = held.get(connection.id());
                boolean awaitsChange = current != null && current.transport == null && current.failureCode != null
                        && AWAIT_CHANGE.contains(current.failureCode);
                if (current != null && current.revision == connection.revision()
                        && (awaitsChange || current.transport != null && current.transport.connected())) {
                    connections.recordState(connection.id(), current.openedAt, current.failureCode, current.failedAt,
                            current.lastMessageAt, now);
                    if (current.transport != null) list(enabled, current, now);
                    continue;
                }
                if (current != null) current.close();
                var next = open(enabled, current, now);
                held.put(connection.id(), next);
                if (next.transport != null) list(enabled, next, now);
                connections.recordState(connection.id(), next.openedAt, next.failureCode, next.failedAt, next.lastMessageAt, now);
            }
            for (var gone = held.entrySet().iterator(); gone.hasNext(); ) {
                var entry = gone.next();
                if (wanted.contains(entry.getKey())) continue;
                entry.getValue().close();
                gone.remove();
                LOG.atInfo().addKeyValue("event", "channel.connection.closed").log("Channel connection closed");
            }
        } catch (RuntimeException failure) {
            // The next pass tries again; a pass that throws must not end the schedule.
            LOG.atWarn().addKeyValue("event", "channel.supervisor.failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Channel supervisor pass failed");
        }
    }

    /**
     * Settles the messages a process claimed and never settled, because it ended while handling them: those claimed
     * before this process began and at least {@link #ABANDONED} ago, so no answer of this process, and none a process
     * that is stopping is still finishing, is touched. A provider does not hand such a message over again.
     */
    private void sweep(Instant now) {
        Instant before = now.minus(ABANDONED);
        if (startedAt.isBefore(before)) before = startedAt;
        try {
            int settled = inbound.settleAbandoned(before);
            if (settled > 0) {
                LOG.atWarn().addKeyValue("event", "channel.message.abandoned").addKeyValue("count", settled)
                        .log("Channel messages left unsettled by an ended process were settled as interrupted");
            }
        } catch (RuntimeException failure) {
            // Swept again at the next pass; the connections are reconciled whatever happens here.
            LOG.atWarn().addKeyValue("event", "channel.message.sweep_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Channel message sweep failed");
        }
    }

    private Held open(JdbcChannelConnectionRepository.Enabled enabled, @Nullable Held previous, Instant now) {
        var connection = enabled.connection();
        var held = new Held(connection.revision(), previous == null ? null : previous.lastMessageAt);
        byte[] secret = null;
        try {
            var envelope = connections.secret(enabled.tenant(), connection.id()).orElseThrow(ChannelException::notFound);
            secret = credentials.cipher(connection.provider()).decrypt(enabled.tenant(), connection.id(), SECRET_PURPOSE, envelope);
            var tenant = enabled.tenant();
            held.transport = adapters.of(connection.provider()).open(connection, new String(secret, StandardCharsets.UTF_8),
                    new ChannelReceiver() {
                        @Override
                        public void accept(ChannelMessage message) {
                            receive(held, message);
                        }

                        @Override
                        public void joined(String chatId, @Nullable String name) {
                            answer(() -> roster.joined(tenant, connection.id(), chatId, name));
                        }
                    });
            var webhook = held.transport.webhook().orElse(null);
            if (webhook != null) {
                // Served before the provider is given its address, so the provider's check reaches this transport.
                held.connectionId = connection.id();
                webhooks.put(connection.id(), new Pushed(webhook, held));
                webhook.register();
            }
            held.openedAt = now;
            LOG.atInfo().addKeyValue("event", "channel.connection.opened").addKeyValue("provider", connection.provider())
                    .log("Channel connection opened");
        } catch (RuntimeException failure) {
            held.close();
            held.failureCode = failure instanceof BusinessException business ? business.code() : "CHANNEL_OPEN_FAILED";
            held.failedAt = now;
            LOG.atWarn().addKeyValue("event", "channel.connection.failed").addKeyValue("provider", connection.provider())
                    .addKeyValue("error_code", held.failureCode).addKeyValue("error_type", failure.getClass().getName())
                    .log("Channel connection could not be opened");
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
        return held;
    }

    /**
     * A message a transport hands over is claimed on the transport's own thread before anything else (MEM-263): a
     * provider that keeps nothing once it has handed a message over, as a poll, loses nothing that was handed over but
     * not yet recorded. It is answered afterwards on a thread of its own.
     */
    /**
     * Reads an open bot's group chats from a provider that lists them, when it connected or {@link #LISTING} after the
     * last read, on a thread of its own. A list the provider could not give whole changes nothing.
     */
    private void list(JdbcChannelConnectionRepository.Enabled enabled, Held held, Instant now) {
        if (held.listedAt != null && held.listedAt.plus(LISTING).isAfter(now)) return;
        held.listedAt = now;
        var connection = enabled.connection();
        var adapter = adapters.of(connection.provider());
        answer(() -> {
            try {
                Instant readAt = clock.instant();
                adapter.groups(connection)
                        .ifPresent(present -> roster.listed(enabled.tenant(), connection.id(), present, readAt));
            } catch (RuntimeException failure) {
                // Read again at the next listing; the groups already known stay.
                LOG.atWarn().addKeyValue("event", "channel.group.list_failed").addKeyValue("provider", connection.provider())
                        .addKeyValue("error_type", failure.getClass().getName()).log("Channel group chats could not be listed");
            }
        });
    }

    private void receive(Held held, ChannelMessage message) {
        held.lastMessageAt = clock.instant();
        inbound.claim(message).ifPresent(this::answer);
    }

    private void answer(Runnable work) {
        try {
            answers.execute(work);
        } catch (RejectedExecutionException stopping) {
            // The process is stopping; the claim stays unsettled and the next process's sweep settles it.
            LOG.atWarn().addKeyValue("event", "channel.message.dropped").log("Channel message not answered while stopping");
        }
    }

    /**
     * Whether a request to the route the provider pushes to may be read (MEM-263), by its headers alone: {@code UNKNOWN}
     * when no transport of that bot receives over HTTP here, {@code REFUSED} when it is not the provider's.
     */
    public Delivery authorize(UUID connectionId, Map<String, String> headers) {
        var pushed = webhooks.get(connectionId);
        if (pushed == null) return Delivery.UNKNOWN;
        return pushed.webhook().authorized(headers) ? Delivery.ACCEPTED : Delivery.REFUSED;
    }

    /**
     * What the route the provider pushes to does with one authorized request (MEM-263): the bot's webhook reads it, each
     * message it carries is claimed before this returns, so the provider is answered only once they are recorded, and
     * answered afterwards on a thread of its own.
     */
    public Delivery deliver(UUID connectionId, Map<String, String> headers, byte[] body) {
        var pushed = webhooks.get(connectionId);
        if (pushed == null) return Delivery.UNKNOWN;
        var messages = pushed.webhook().accept(headers, body).orElse(null);
        if (messages == null) return Delivery.REFUSED;
        for (var message : messages) {
            pushed.held().lastMessageAt = clock.instant();
            inbound.claim(message).ifPresent(this::answer);
        }
        return Delivery.ACCEPTED;
    }

    /** {@code UNKNOWN}: no transport of that bot receives over HTTP here; {@code REFUSED}: not the provider's request. */
    public enum Delivery { ACCEPTED, REFUSED, UNKNOWN }

    private record Pushed(ChannelWebhook webhook, Held held) {}

    /** One open transport, or one that failed to open, at a revision of its bot. */
    private final class Held {
        final long revision;
        volatile @Nullable Instant lastMessageAt;
        @Nullable ChannelTransport transport;
        @Nullable UUID connectionId;
        @Nullable Instant openedAt;
        @Nullable String failureCode;
        @Nullable Instant failedAt;
        /** When the bot's group chats were last read from its provider. */
        @Nullable Instant listedAt;

        Held(long revision, @Nullable Instant lastMessageAt) {
            this.revision = revision;
            this.lastMessageAt = lastMessageAt;
        }

        void close() {
            if (connectionId != null) webhooks.computeIfPresent(connectionId, (_, pushed) -> pushed.held() == this ? null : pushed);
            if (transport == null) return;
            try {
                transport.close();
            } catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "channel.connection.close_failed")
                        .addKeyValue("error_type", failure.getClass().getName()).log("Channel connection did not close cleanly");
            }
            transport = null;
        }
    }
}
