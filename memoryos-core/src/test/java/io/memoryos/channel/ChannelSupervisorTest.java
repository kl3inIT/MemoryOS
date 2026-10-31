package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelLeaseRepository;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChannelSupervisorTest {
    private static final String SECRET = "lark-app-secret";

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TenantId tenant;
    private UUID connection;
    private final FakeAdapter adapter = new FakeAdapter();
    private final ChannelInboundService inbound = mock(ChannelInboundService.class);
    private final ChannelGroupRoster roster = mock(ChannelGroupRoster.class);
    private ChannelSupervisor supervisor;
    private ChannelCredentialConfiguration credentials;
    private final MovingClock clock = new MovingClock();
    private final List<ChannelSupervisor> others = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Channel','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        UUID bot = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", bot).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", bot).update();
        credentials = new ChannelCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test-1");
        connection = UUID.randomUUID();
        var envelope = credentials.cipher(ChannelProvider.LARK).encrypt(tenant, connection, "app-secret",
                SECRET.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO channel_connections(tenant_id, id, provider, name, settings, app_id, secret_ciphertext, secret_nonce,
                                                secret_key_version, notice_language, enabled, service_account_id)
                VALUES (:tenant, :id, 'LARK', 'Bot', '{"region": "LARK"}', 'cli_a1b2c3d4e5f6', :secret, :nonce, :version, 'vi', TRUE, :account)
                """).param("tenant", tenant.value()).param("id", connection).param("secret", envelope.ciphertext())
                .param("nonce", envelope.nonce()).param("version", envelope.keyVersion()).param("account", bot).update();
        supervisor = supervisor(adapter, new JdbcChannelLeaseRepository(jdbc), "channels-a:8082");
    }

    /** A process that receives, at {@code address}, sharing the database with every other one of the test. */
    private ChannelSupervisor supervisor(FakeAdapter platform, JdbcChannelLeaseRepository leases, String address) {
        var properties = new ChannelProperties(Duration.ofHours(1), Duration.ofHours(1), Duration.ofDays(90), 10,
                Duration.ofSeconds(3), "", Duration.ofHours(4), address);
        return new ChannelSupervisor(new JdbcChannelConnectionRepository(jdbc), leases, credentials,
                new ChannelAdapters(List.of(platform)), inbound, roster, properties, clock);
    }

    @AfterEach
    void close() {
        others.forEach(ChannelSupervisor::stop);
        supervisor.stop();
        if (dataSource != null) dataSource.close();
    }

    @Test
    void aBotSwitchedOnIsOpenedWithItsSecretAndItsStateIsWritten() {
        supervisor.pass();

        assertEquals(List.of(SECRET), adapter.secrets);
        var state = state();
        assertNotNull(state.openedAt());
        assertNull(state.failureCode());
        // A second pass keeps what is open and only refreshes the heartbeat.
        supervisor.pass();
        assertEquals(1, adapter.opened.size());
    }

    /** MEM-263: a message is claimed on the transport's thread, so a poll's message is recorded before anything else. */
    @Test
    void aMessageIsClaimedOnTheAdaptersThreadAndAnsweredOffIt() throws InterruptedException {
        supervisor.start();
        while (adapter.receivers.isEmpty()) Thread.onSpinWait();
        var message = new ChannelMessage(connection, "m1", "oc_chat", ChannelMessage.Kind.DIRECT, "ou_ha", null, new ChannelMessage.Organisations("org", "org"), true,
                ChannelMessage.Type.TEXT, "Hello", null);
        var answeredOn = new LinkedBlockingQueue<Thread>();
        when(inbound.claim(message)).thenReturn(Optional.of(() -> answeredOn.add(Thread.currentThread())));

        adapter.receivers.getFirst().accept(message);

        verify(inbound).claim(message);
        var thread = answeredOn.poll(5, TimeUnit.SECONDS);
        assertNotNull(thread);
        assertNotEquals(Thread.currentThread(), thread);
    }

    /** MEM-273 5d: a provider that lists a bot's groups is read when the bot connects, and not again before LISTING. */
    @Test
    void aConnectedBotsGroupsAreReadOnceAndAJoinedGroupIsRecordedAtOnce() {
        var present = List.of(new ChannelGroupPresence("oc_accounting", "Phòng Kế toán"));
        adapter.listing = Optional.of(present);
        supervisor.pass();
        verify(roster, timeout(5000)).listed(eq(tenant), eq(connection), eq(present), any());
        supervisor.pass();
        verify(roster, after(300).times(1)).listed(any(), any(), any(), any());

        adapter.receivers.getFirst().joined("oc_hr", "Phòng Nhân sự");
        verify(roster, timeout(5000)).joined(tenant, connection, "oc_hr", "Phòng Nhân sự");
    }

    /** A provider that cannot list its groups (Zalo) or gave no whole list changes nothing. */
    @Test
    void noListChangesNoGroup() {
        supervisor.pass();
        verify(roster, after(300).never()).listed(any(), any(), any(), any());
    }

    @Test
    void eachPassSettlesWhatAnEndedProcessLeftUnsettled() {
        supervisor.start();
        verify(inbound, timeout(5000)).settleAbandoned(argThat(before -> !before.isAfter(Instant.now().minus(ChannelSupervisor.ABANDONED))));
    }

    @Test
    void aBotSwitchedOffOrChangedIsClosedOrReopened() {
        supervisor.pass();
        jdbc.sql("UPDATE channel_connections SET revision = revision + 1").update();
        supervisor.pass();
        assertEquals(2, adapter.opened.size());
        assertTrue(adapter.opened.getFirst().closed, "the old transport is closed before the new one opens");

        jdbc.sql("UPDATE channel_connections SET enabled = FALSE").update();
        supervisor.pass();
        assertTrue(adapter.opened.get(1).closed);
        assertEquals(2, adapter.opened.size());
    }

    @Test
    void aDroppedTransportIsReopenedAndAFailedOpenIsRecordedAndRetried() {
        supervisor.pass();
        adapter.opened.getFirst().connected = false;
        supervisor.pass();
        assertEquals(2, adapter.opened.size());

        adapter.failing = true;
        adapter.opened.get(1).connected = false;
        supervisor.pass();
        var state = state();
        assertEquals("CHANNEL_OPEN_FAILED", state.failureCode());
        assertNull(state.openedAt());

        adapter.failing = false;
        supervisor.pass();
        assertNotNull(state().openedAt());
    }

    /** MEM-263: a provider that pushes reaches the bot's webhook through the route, and is answered after the claim. */
    @Test
    void aPushedMessageIsClaimedBeforeTheProviderIsAnswered() {
        adapter.pushes = true;
        var duringRegister = new ArrayList<ChannelSupervisor.Delivery>();
        adapter.onRegister = () -> duringRegister.add(supervisor.deliver(connection, Map.of("secret", "s"), new byte[0]));
        when(inbound.claim(any())).thenReturn(Optional.empty());

        supervisor.pass();

        assertEquals(List.of(ChannelSupervisor.Delivery.ACCEPTED), duringRegister,
                "the provider's check of the address reaches the transport it is given for");
        assertNotNull(state().openedAt());
        assertEquals(ChannelSupervisor.Delivery.ACCEPTED,
                supervisor.deliver(connection, Map.of("secret", "s"), "Hello".getBytes(StandardCharsets.UTF_8)));
        verify(inbound).claim(argThat(message -> message.text().equals("Hello")));
        assertEquals(ChannelSupervisor.Delivery.REFUSED, supervisor.deliver(connection, Map.of("secret", "x"), new byte[0]));
        assertEquals(ChannelSupervisor.Delivery.UNKNOWN, supervisor.deliver(UUID.randomUUID(), Map.of("secret", "s"), new byte[0]));
        // The route asks before it reads a body.
        assertEquals(ChannelSupervisor.Delivery.ACCEPTED, supervisor.authorize(connection, Map.of("secret", "s")));
        assertEquals(ChannelSupervisor.Delivery.REFUSED, supervisor.authorize(connection, Map.of("secret", "x")));
        assertEquals(ChannelSupervisor.Delivery.UNKNOWN, supervisor.authorize(UUID.randomUUID(), Map.of("secret", "s")));

        jdbc.sql("UPDATE channel_connections SET enabled = FALSE").update();
        supervisor.pass();
        assertEquals(ChannelSupervisor.Delivery.UNKNOWN, supervisor.deliver(connection, Map.of("secret", "s"), new byte[0]));
    }

    @Test
    void aWebhookTheProviderCannotReachWaitsForTheBotToChange() {
        adapter.pushes = true;
        adapter.onRegister = () -> { throw ChannelException.webhookUnreachable("webhook.err.dns"); };

        supervisor.pass();
        assertEquals("CHANNEL_WEBHOOK_UNREACHABLE", state().failureCode());
        assertTrue(adapter.opened.getFirst().closed);
        assertEquals(ChannelSupervisor.Delivery.UNKNOWN, supervisor.deliver(connection, Map.of("secret", "s"), new byte[0]));
        // Opening again would only spend the provider's checks: it waits for an administrator to change the bot.
        supervisor.pass();
        assertEquals(1, adapter.opened.size());

        adapter.onRegister = () -> { };
        jdbc.sql("UPDATE channel_connections SET revision = revision + 1").update();
        supervisor.pass();
        assertEquals(2, adapter.opened.size());
        assertNotNull(state().openedAt());
    }

    @Test
    void stoppingClosesEveryConnection() {
        supervisor.pass();
        supervisor.start();
        supervisor.stop();
        assertFalse(supervisor.isRunning());
        assertTrue(adapter.opened.stream().allMatch(transport -> transport.closed));
    }

    /** MEM-283: two processes share the bots; one holds each, and stopping hands it to the other at once. */
    @Test
    void twoProcessesNeverHoldOneBotAndAStoppingOneHandsItOver() {
        var second = new FakeAdapter();
        var other = supervisor(second, new JdbcChannelLeaseRepository(jdbc), "channels-b:8082");
        others.add(other);

        supervisor.pass();
        other.pass();
        assertEquals(1, adapter.opened.size());
        assertTrue(second.opened.isEmpty(), "the bot another process holds is not opened");
        assertEquals(Optional.of("channels-a:8082"), other.holderAddress(connection),
                "a webhook request reaching the other process is passed to the holder");
        assertEquals(Optional.empty(), supervisor.holderAddress(connection));

        supervisor.start();
        supervisor.stop();
        other.pass();
        assertEquals(1, second.opened.size(), "a lease handed back is taken at the next pass");
        assertNotNull(state().openedAt());
    }

    /** MEM-283: a holder whose lease another process took closes the bot at its next renewal. */
    @Test
    void aLeaseTakenElsewhereClosesTheBotAtTheNextRenewal() {
        supervisor.pass();
        jdbc.sql("""
                UPDATE channel_connection_state SET holder = 'elsewhere', holder_address = 'channels-b:8082',
                    lease_epoch = lease_epoch + 1, lease_until = now() + INTERVAL '90 seconds'
                """).update();

        supervisor.renew();
        assertTrue(adapter.opened.getFirst().closed);
        supervisor.pass();
        assertEquals(1, adapter.opened.size(), "a bot another process holds is not opened again");
        assertEquals(Optional.of("channels-b:8082"), supervisor.holderAddress(connection));
    }

    /** MEM-283: a holder that cannot renew stops before its lease can end. */
    @Test
    void aHolderThatCannotRenewClosesTheBotBeforeItsLeaseEnds() {
        var leases = spy(new JdbcChannelLeaseRepository(jdbc));
        var fenced = supervisor(adapter, leases, "channels-c:8082");
        others.add(fenced);
        fenced.pass();
        doThrow(new IllegalStateException("database unreachable")).when(leases).renew(any(), any(), any());

        clock.advance(ChannelSupervisor.FENCE.minusSeconds(1));
        fenced.renew();
        assertFalse(adapter.opened.getFirst().closed, "still within the fence");
        clock.advance(Duration.ofSeconds(1));
        fenced.renew();
        assertTrue(adapter.opened.getFirst().closed, "closed before the lease can end");
    }

    /** MEM-283: a write of an epoch that lost the bot changes nothing. */
    @Test
    void aStaleEpochCannotOverwriteItsSuccessor() {
        var leases = new JdbcChannelLeaseRepository(jdbc);
        long first = leases.hold(connection, "a", "channels-a:8082", Duration.ofSeconds(90)).orElseThrow();
        assertTrue(leases.hold(connection, "b", "channels-b:8082", Duration.ofSeconds(90)).isEmpty(),
                "a live lease is not taken");
        jdbc.sql("UPDATE channel_connection_state SET lease_until = now() - INTERVAL '1 second'").update();
        long second = leases.hold(connection, "b", "channels-b:8082", Duration.ofSeconds(90)).orElseThrow();
        assertEquals(first + 1, second, "a change of hands starts a new epoch");

        assertFalse(leases.recordState(connection, "a", first, Instant.now(), null, null, null, Instant.now()));
        assertTrue(leases.recordState(connection, "b", second, Instant.now(), null, null, null, Instant.now()));
        leases.recordPairing(connection, "a", first, "stale", Instant.now().plusSeconds(60), Instant.now());
        assertEquals(0, pairings());
        assertEquals(Set.of(), leases.renew("a", List.of(connection), Duration.ofSeconds(90)));
        assertEquals(Set.of(connection), leases.renew("b", List.of(connection), Duration.ofSeconds(90)));
        assertEquals(second, leases.hold(connection, "b", "channels-b:8082", Duration.ofSeconds(90)).orElseThrow(),
                "the holder keeps its epoch");
    }

    /** MEM-283: a transport stopped for a reason only an administrator can mend waits for the bot to change. */
    @Test
    void aSessionWhatsAppEndedWaitsForTheBotToChange() {
        supervisor.pass();
        var first = adapter.opened.getFirst();
        first.stopped = ChannelException.whatsappLoggedOut();
        first.connected = false;

        supervisor.pass();
        assertEquals("CHANNEL_WHATSAPP_LOGGED_OUT", state().failureCode());
        assertNull(state().openedAt());
        supervisor.pass();
        assertEquals(1, adapter.opened.size(), "relinking in a loop would not mend it");

        jdbc.sql("UPDATE channel_connections SET revision = revision + 1").update();
        supervisor.pass();
        assertEquals(2, adapter.opened.size());
    }

    /** MEM-283: the code a bot being linked shows is kept for its page and cleared once scanned. */
    @Test
    void aPairingCodeIsKeptUntilItIsScanned() {
        adapter.pairs = true;
        supervisor.pass();
        var expires = Instant.now().plusSeconds(60);

        adapter.receivers.getFirst().pairing("2@abc", expires);
        awaitPairings(1);
        var shown = new JdbcChannelLeaseRepository(jdbc).pairing(tenant, connection, Instant.now()).orElseThrow();
        assertEquals("2@abc", shown.code());
        assertEquals(expires.getEpochSecond(), shown.expiresAt().getEpochSecond());

        adapter.receivers.getFirst().paired();
        awaitPairings(0);
    }

    /** MEM-283: a bot removed while it is held is forgotten by its provider, as WhatsApp logs the device out. */
    @Test
    void aRemovedBotIsForgottenAndASwitchedOffOneIsNot() {
        supervisor.pass();
        jdbc.sql("UPDATE channel_connections SET enabled = FALSE").update();
        supervisor.pass();
        assertTrue(adapter.forgotten.isEmpty());

        jdbc.sql("UPDATE channel_connections SET enabled = TRUE").update();
        supervisor.pass();
        jdbc.sql("DELETE FROM channel_connections").update();
        supervisor.pass();
        assertEquals(List.of(connection), adapter.forgotten);
    }

    private int pairings() {
        return jdbc.sql("SELECT count(*) FROM channel_pairings").query(Integer.class).single();
    }

    private void awaitPairings(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pairings() != expected) {
            if (System.nanoTime() > deadline) throw new AssertionError("expected " + expected + " pairing codes");
            Thread.onSpinWait();
        }
    }

    private State state() {
        return jdbc.sql("SELECT opened_at, last_failure_code FROM channel_connection_state WHERE connection_id = :id")
                .param("id", connection)
                .query((row, ignored) -> {
                    Timestamp opened = row.getTimestamp("opened_at");
                    return new State(opened == null ? null : opened.toInstant(), row.getString("last_failure_code"));
                }).single();
    }

    private record State(@Nullable Instant openedAt, @Nullable String failureCode) {}

    private static final class FakeAdapter implements ChannelAdapter {
        final List<String> secrets = new ArrayList<>();
        final List<FakeTransport> opened = new ArrayList<>();
        final List<ChannelReceiver> receivers = new CopyOnWriteArrayList<>();
        volatile boolean failing;
        volatile boolean pushes;
        volatile Runnable onRegister = () -> { };
        volatile Optional<List<ChannelGroupPresence>> listing = Optional.empty();
        volatile boolean pairs;
        final List<UUID> forgotten = new CopyOnWriteArrayList<>();

        @Override public ChannelProvider provider() { return ChannelProvider.LARK; }

        @Override
        public ChannelTransport open(ChannelConnection connection, String secret, ChannelReceiver receiver) {
            if (failing) throw new IllegalStateException("provider refused");
            secrets.add(secret);
            receivers.add(receiver);
            var transport = new FakeTransport(pushes ? new FakeWebhook(connection.id(), onRegister) : null);
            opened.add(transport);
            return transport;
        }

        @Override public ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting) { throw new UnsupportedOperationException(); }

        @Override public void notice(ChannelConnection connection, ChannelMessage message, String text) {}

        @Override public void acknowledge(ChannelConnection connection, ChannelMessage message) {}

        @Override public ChannelFeatures features() { return new ChannelFeatures(true, true, 150_000, pairs); }

        @Override public void forget(ChannelConnection connection) { forgotten.add(connection.id()); }

        @Override public ChannelSender sender(ChannelConnection connection, String senderId) { return ChannelSender.UNKNOWN; }

        @Override public ChannelChat chat(ChannelConnection connection, String chatId) { return ChannelChat.UNNAMED; }

        @Override public Optional<List<ChannelGroupPresence>> groups(ChannelConnection connection) { return listing; }

        @Override public ChannelVerification verify(ChannelCredentials credentials) { throw new UnsupportedOperationException(); }

        @Override public Map<String, String> settings(Map<String, String> requested) { return requested; }
    }

    private static final class FakeTransport implements ChannelTransport {
        private final @Nullable ChannelWebhook webhook;
        volatile boolean connected = true;
        volatile boolean closed;
        volatile @Nullable ChannelException stopped;

        FakeTransport(@Nullable ChannelWebhook webhook) { this.webhook = webhook; }

        @Override public boolean connected() { return connected && !closed; }

        @Override public Optional<ChannelException> stopped() { return Optional.ofNullable(stopped); }

        @Override public Optional<ChannelWebhook> webhook() { return Optional.ofNullable(webhook); }

        @Override public void close() { closed = true; }
    }

    /** The wall clock, moved on by a test to reach the fence without waiting for it. */
    private static final class MovingClock extends Clock {
        private volatile Duration offset = Duration.ZERO;

        void advance(Duration by) { offset = offset.plus(by); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }

        @Override public Instant instant() { return Instant.now().plus(offset); }
    }

    /** Accepts a request whose {@code secret} header is {@code s}; its body is one message's text. */
    private record FakeWebhook(UUID connection, Runnable onRegister) implements ChannelWebhook {
        @Override
        public boolean authorized(Map<String, String> headers) {
            return "s".equals(headers.get("secret"));
        }

        @Override
        public Optional<List<ChannelMessage>> accept(Map<String, String> headers, byte[] body) {
            if (!authorized(headers)) return Optional.empty();
            if (body.length == 0) return Optional.of(List.of());
            return Optional.of(List.of(new ChannelMessage(connection, "m1", "chat", ChannelMessage.Kind.DIRECT, "u1", null,
                    null, true, ChannelMessage.Type.TEXT, new String(body, StandardCharsets.UTF_8), null)));
        }

        @Override public void register() { onRegister.run(); }
    }
}
