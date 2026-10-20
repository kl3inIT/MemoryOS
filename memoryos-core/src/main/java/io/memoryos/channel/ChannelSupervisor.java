package io.memoryos.channel;

import io.memoryos.BusinessException;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelLeaseRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
 * Holds open the transport of every bot that is switched on and that this process holds the lease of, in the
 * processes that receive (MEM-246, MEM-283). Every {@link #PASS} it takes the lease of a bot nobody holds, opens a bot
 * switched on since, closes one switched off, removed or taken by another process, reopens one whose connection
 * changed or dropped, and writes what it saw to {@code channel_connection_state}. A failed open is recorded with its
 * typed code and tried again at the next pass. Each message an adapter hands over is answered on a virtual thread of
 * its own, so the adapter's acknowledgement to the provider never waits for an answer. The group chats a bot is in are
 * read from a provider that lists them when the bot connects and every {@link #LISTING} after (MEM-273), on a virtual
 * thread, so a slow list never holds a pass.
 *
 * <p>A lease lasts {@link #LEASE} on the database's clock and is renewed every {@link #RENEWAL} on a thread apart from
 * the pass, so a slow open never delays a renewal. A bot whose lease was not renewed for {@link #FENCE} is closed by
 * this process before the lease can end, so two processes never hold one bot, as Kubernetes leader election stops
 * acting once it cannot renew by its deadline; stopping hands every lease back at once.
 *
 * <p>It is no component: only the process that receives imports it, so no other process opens a connection.
 */
public class ChannelSupervisor implements SmartLifecycle {
    static final Duration PASS = Duration.ofSeconds(30);
    static final Duration LEASE = Duration.ofSeconds(90);
    static final Duration RENEWAL = Duration.ofSeconds(10);
    static final Duration FENCE = Duration.ofSeconds(60);
    /** How old an unsettled claim whose bot no live process held since is before it is taken for abandoned. */
    static final Duration ABANDONED = Duration.ofMinutes(10);
    /** How often an open bot's group chats are read again from a provider that lists them. */
    static final Duration LISTING = Duration.ofMinutes(10);
    private static final Logger LOG = LoggerFactory.getLogger(ChannelSupervisor.class);
    private static final String SECRET_PURPOSE = "app-secret";

    private final JdbcChannelConnectionRepository connections;
    private final JdbcChannelLeaseRepository leases;
    private final ChannelCredentialConfiguration credentials;
    private final ChannelAdapters adapters;
    private final ChannelInboundService inbound;
    private final ChannelGroupRoster roster;
    private final Clock clock;
    /** This process, as the leases name it: unique to one start, so a restarted process never mistakes an old lease. */
    private final String holder;
    /** Where another process reaches this one's webhook route. */
    private final String address;
    /**
     * Failures that opening again cannot mend until an administrator changes the bot: the provider could not reach the
     * webhook, this deployment has no address to give it, or WhatsApp ended the bot's session (MEM-283). They wait for
     * the next revision rather than being tried every pass, which would spend the provider's daily webhook checks or
     * relink a number in a loop.
     */
    private static final Set<String> AWAIT_CHANGE = Set.of("CHANNEL_WEBHOOK_UNREACHABLE", "CHANNEL_WEB_URL_MISSING",
            "CHANNEL_WHATSAPP_LOGGED_OUT", "CHANNEL_WHATSAPP_BANNED", "CHANNEL_WHATSAPP_REPLACED",
            "CHANNEL_WHATSAPP_WRONG_NUMBER", "CHANNEL_WHATSAPP_NUMBER_IN_USE");

    /** The bots this process holds, read by the renewal without the pass's lock. */
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    /** The webhook of each bot whose transport receives over HTTP, read by the route without the pass's lock. */
    private final Map<UUID, Pushed> webhooks = new ConcurrentHashMap<>();
    private final ExecutorService answers = Executors.newVirtualThreadPerTaskExecutor();
    private @Nullable ScheduledExecutorService passes;
    private @Nullable ScheduledExecutorService renewals;

    @Autowired
    public ChannelSupervisor(JdbcChannelConnectionRepository connections, JdbcChannelLeaseRepository leases,
                             ChannelCredentialConfiguration credentials, ChannelAdapters adapters,
                             ChannelInboundService inbound, ChannelGroupRoster roster, ChannelProperties properties) {
        this(connections, leases, credentials, adapters, inbound, roster, properties, Clock.systemUTC());
    }

    ChannelSupervisor(JdbcChannelConnectionRepository connections, JdbcChannelLeaseRepository leases,
                      ChannelCredentialConfiguration credentials, ChannelAdapters adapters, ChannelInboundService inbound,
                      ChannelGroupRoster roster, ChannelProperties properties, Clock clock) {
        this.connections = connections;
        this.leases = leases;
        this.credentials = credentials;
        this.adapters = adapters;
        this.inbound = inbound;
        this.roster = roster;
        this.clock = clock;
        this.address = properties.address();
        this.holder = (address.isEmpty() ? "channels" : address) + "/" + UUID.randomUUID();
    }

    @Override
    public synchronized void start() {
        if (passes != null) return;
        passes = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("channel-supervisor").factory());
        renewals = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("channel-leases").factory());
        passes.scheduleWithFixedDelay(this::pass, 0, PASS.toMillis(), TimeUnit.MILLISECONDS);
        renewals.scheduleWithFixedDelay(this::renew, RENEWAL.toMillis(), RENEWAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Closes every connection and hands its lease back, then lets the answers in progress end. */
    @Override
    public synchronized void stop() {
        if (passes == null) return;
        passes.shutdownNow();
        passes = null;
        if (renewals != null) renewals.shutdownNow();
        renewals = null;
        held.values().forEach(Held::close);
        held.clear();
        try {
            leases.release(holder);
        } catch (RuntimeException failure) {
            // The leases end on their own within LEASE; another process then takes the bots.
            LOG.atWarn().addKeyValue("event", "channel.lease.release_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Channel leases were not handed back");
        }
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

    /** One reconciliation of what is open here with what is switched on and leased to this process. */
    synchronized void pass() {
        try {
            Instant now = clock.instant();
            sweep(now);
            var wanted = new HashSet<UUID>();
            for (var enabled : connections.listEnabled()) {
                var connection = enabled.connection();
                var current = held.get(connection.id());
                OptionalLong epoch = leases.hold(connection.id(), holder, address, LEASE);
                if (epoch.isEmpty()) {
                    // Another process holds the bot.
                    if (current != null) drop(connection.id(), current, "channel.lease.lost");
                    continue;
                }
                wanted.add(connection.id());
                if (current != null && current.epoch != epoch.getAsLong()) {
                    // The lease ended and was taken again: what was open is another epoch's.
                    current.close();
                    current = null;
                }
                if (current != null) current.renewedAt = now;
                if (current != null) settleStopped(current, now);
                boolean awaitsChange = current != null && !current.open() && current.failureCode != null
                        && AWAIT_CHANGE.contains(current.failureCode);
                if (current != null && current.revision == connection.revision() && (awaitsChange || current.live())) {
                    record(current, now);
                    if (current.open()) list(enabled, current, now);
                    continue;
                }
                if (current != null) current.close();
                var next = open(enabled, current, epoch.getAsLong(), now);
                held.put(connection.id(), next);
                if (next.open()) list(enabled, next, now);
                record(next, now);
            }
            for (var entry : List.copyOf(held.entrySet())) {
                if (wanted.contains(entry.getKey())) continue;
                var gone = entry.getValue();
                drop(entry.getKey(), gone, "channel.connection.closed");
                if (!connections.exists(entry.getKey())) forget(gone);
                leases.release(entry.getKey(), holder, gone.epoch);
            }
        } catch (RuntimeException failure) {
            // The next pass tries again; a pass that throws must not end the schedule.
            LOG.atWarn().addKeyValue("event", "channel.supervisor.failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Channel supervisor pass failed");
        }
    }

    /**
     * Renews the lease of every bot held here, and closes each one whose lease another process took or that has not
     * been renewed for {@link #FENCE}, before its lease can end.
     */
    void renew() {
        Instant now = clock.instant();
        Set<UUID> renewed = null;
        try {
            renewed = leases.renew(holder, Set.copyOf(held.keySet()), LEASE);
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "channel.lease.renew_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Channel leases could not be renewed");
        }
        for (var entry : List.copyOf(held.entrySet())) {
            var bot = entry.getValue();
            if (renewed != null && renewed.contains(entry.getKey())) {
                bot.renewedAt = now;
            } else if (renewed != null || !bot.renewedAt.plus(FENCE).isAfter(now)) {
                // Taken by another process, or unrenewed for too long: stop before two processes hold it.
                bot.fence();
            }
        }
    }

    private void drop(UUID id, Held gone, String event) {
        gone.close();
        held.remove(id, gone);
        LOG.atInfo().addKeyValue("event", event).log("Channel connection closed");
    }

    /** A removed bot's provider forgets it, as WhatsApp logs its device out; best effort. */
    private void forget(Held gone) {
        try {
            adapters.of(gone.connection.provider()).forget(gone.connection);
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "channel.connection.forget_failed")
                    .addKeyValue("provider", gone.connection.provider())
                    .addKeyValue("error_type", failure.getClass().getName()).log("Channel provider did not forget the bot");
        }
    }

    /** A transport that stopped for a reason only an administrator can mend is closed with that reason recorded. */
    private void settleStopped(Held current, Instant now) {
        var stopped = current.stopped();
        if (stopped.isEmpty()) return;
        current.close();
        current.failureCode = stopped.get().code();
        current.failedAt = now;
        current.openedAt = null;
        LOG.atWarn().addKeyValue("event", "channel.connection.stopped").addKeyValue("error_code", current.failureCode)
                .log("Channel connection stopped");
    }

    private void record(Held current, Instant now) {
        if (!leases.recordState(current.connectionId, holder, current.epoch, current.openedAt, current.failureCode,
                current.failedAt, current.lastMessageAt, now)) {
            // The lease moved on between the hold and this write; the next renewal closes the bot.
            LOG.atWarn().addKeyValue("event", "channel.lease.stale_write").log("Channel state of a lost lease not written");
        }
    }

    /**
     * Settles the messages a process claimed and never settled because it ended while handling them: those claimed at
     * least {@link #ABANDONED} ago whose bot no live process has held since they were received, so no answer of a live
     * process, and none a process that is stopping is still finishing, is touched. A provider does not hand such a
     * message over again.
     */
    private void sweep(Instant now) {
        try {
            int settled = inbound.settleAbandoned(now.minus(ABANDONED));
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

    private Held open(JdbcChannelConnectionRepository.Enabled enabled, @Nullable Held previous, long epoch, Instant now) {
        var connection = enabled.connection();
        var held = new Held(connection, epoch, previous == null ? null : previous.lastMessageAt, now);
        byte[] secret = null;
        try {
            var adapter = adapters.of(connection.provider());
            if (!adapter.features().pairs()) {
                var envelope = connections.secret(enabled.tenant(), connection.id()).orElseThrow(ChannelException::notFound);
                secret = credentials.cipher(connection.provider()).decrypt(enabled.tenant(), connection.id(), SECRET_PURPOSE,
                        envelope);
            }
            var tenant = enabled.tenant();
            // A bot linked by a code has no secret (MEM-283); its adapter is given an empty one.
            var transport = adapter.open(connection, secret == null ? "" : new String(secret, StandardCharsets.UTF_8),
                    new ChannelReceiver() {
                        @Override
                        public void accept(ChannelMessage message) {
                            receive(held, message);
                        }

                        @Override
                        public void joined(String chatId, @Nullable String name) {
                            answer(() -> roster.joined(tenant, connection.id(), chatId, name));
                        }

                        @Override
                        public void pairing(String code, Instant expiresAt) {
                            answer(() -> leases.recordPairing(connection.id(), holder, epoch, code, expiresAt,
                                    clock.instant()));
                        }

                        @Override
                        public void paired() {
                            answer(() -> leases.clearPairing(connection.id(), holder, epoch));
                        }
                    });
            held.transport = transport;
            var webhook = transport.webhook().orElse(null);
            if (webhook != null) {
                // Served before the provider is given its address, so the provider's check reaches this transport.
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

    /**
     * A message a transport hands over is claimed on the transport's own thread before anything else (MEM-263): a
     * provider that keeps nothing once it has handed a message over, as a poll, loses nothing that was handed over but
     * not yet recorded. It is answered afterwards on a thread of its own.
     */
    private void receive(Held held, ChannelMessage message) {
        held.lastMessageAt = clock.instant();
        inbound.claim(message).ifPresent(this::answer);
    }

    private void answer(Runnable work) {
        try {
            answers.execute(work);
        } catch (RejectedExecutionException stopping) {
            // The process is stopping; the claim stays unsettled and a live process's sweep settles it.
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

    /**
     * Where the live process that holds a bot this one does not serves its webhook (MEM-283), so the route passes the
     * provider's request on; empty when no live process holds it.
     */
    public Optional<String> holderAddress(UUID connectionId) {
        return leases.liveAddress(connectionId, holder);
    }

    /** {@code UNKNOWN}: no transport of that bot receives over HTTP here; {@code REFUSED}: not the provider's request. */
    public enum Delivery { ACCEPTED, REFUSED, UNKNOWN }

    private record Pushed(ChannelWebhook webhook, Held held) {}

    /**
     * One open transport, or one that failed to open, at a revision of its bot and an epoch of its lease. Its transport
     * is closed under its own lock, since the renewal may fence it while a pass reads it.
     */
    private final class Held {
        final UUID connectionId;
        final ChannelConnection connection;
        final long revision;
        final long epoch;
        volatile Instant renewedAt;
        volatile @Nullable Instant lastMessageAt;
        private @Nullable ChannelTransport transport;
        @Nullable Instant openedAt;
        @Nullable String failureCode;
        @Nullable Instant failedAt;
        /** When the bot's group chats were last read from its provider. */
        @Nullable Instant listedAt;

        Held(ChannelConnection connection, long epoch, @Nullable Instant lastMessageAt, Instant renewedAt) {
            this.connectionId = connection.id();
            this.connection = connection;
            this.revision = connection.revision();
            this.epoch = epoch;
            this.lastMessageAt = lastMessageAt;
            this.renewedAt = renewedAt;
        }

        synchronized boolean open() {
            return transport != null;
        }

        synchronized boolean live() {
            return transport != null && transport.connected();
        }

        synchronized Optional<ChannelException> stopped() {
            return transport == null || transport.connected() ? Optional.empty() : transport.stopped();
        }

        /** Closes the transport of a bot this process may no longer hold; the next pass takes the lease again or not. */
        synchronized void fence() {
            if (transport == null) return;
            close();
            failureCode = "CHANNEL_LEASE_LOST";
            failedAt = clock.instant();
            openedAt = null;
            LOG.atWarn().addKeyValue("event", "channel.lease.fenced").log("Channel connection closed for a lost lease");
        }

        synchronized void close() {
            webhooks.computeIfPresent(connectionId, (_, pushed) -> pushed.held() == this ? null : pushed);
            if (transport == null) return;
            boolean pairs = adapters.of(connection.provider()).features().pairs();
            try {
                transport.close();
            } catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "channel.connection.close_failed")
                        .addKeyValue("error_type", failure.getClass().getName()).log("Channel connection did not close cleanly");
            }
            transport = null;
            if (pairs) answer(() -> leases.clearPairing(connectionId, holder, epoch));
        }
    }
}
