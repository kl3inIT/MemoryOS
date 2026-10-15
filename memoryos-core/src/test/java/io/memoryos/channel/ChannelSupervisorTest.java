package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
    private ChannelSupervisor supervisor;

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
        var credentials = new ChannelCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test-1");
        connection = UUID.randomUUID();
        var envelope = credentials.cipher(ChannelProvider.LARK).encrypt(tenant, connection, "app-secret",
                SECRET.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO channel_connections(tenant_id, id, provider, name, settings, app_id, secret_ciphertext, secret_nonce,
                                                secret_key_version, notice_language, enabled, service_account_id)
                VALUES (:tenant, :id, 'LARK', 'Bot', '{"region": "LARK"}', 'cli_a1b2c3d4e5f6', :secret, :nonce, :version, 'vi', TRUE, :account)
                """).param("tenant", tenant.value()).param("id", connection).param("secret", envelope.ciphertext())
                .param("nonce", envelope.nonce()).param("version", envelope.keyVersion()).param("account", bot).update();
        supervisor = new ChannelSupervisor(new JdbcChannelConnectionRepository(jdbc), credentials,
                new ChannelAdapters(List.of(adapter)), inbound);
    }

    @AfterEach
    void close() {
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
        final List<Consumer<ChannelMessage>> receivers = new CopyOnWriteArrayList<>();
        volatile boolean failing;
        volatile boolean pushes;
        volatile Runnable onRegister = () -> { };

        @Override public ChannelProvider provider() { return ChannelProvider.LARK; }

        @Override
        public ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver) {
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

        @Override public ChannelFeatures features() { return new ChannelFeatures(true, true, 150_000); }

        @Override public ChannelSender sender(ChannelConnection connection, String senderId) { return ChannelSender.UNKNOWN; }

        @Override public boolean externalChat(ChannelConnection connection, String chatId) { return false; }

        @Override public ChannelVerification verify(ChannelCredentials credentials) { throw new UnsupportedOperationException(); }

        @Override public Map<String, String> settings(Map<String, String> requested) { return requested; }
    }

    private static final class FakeTransport implements ChannelTransport {
        private final @Nullable ChannelWebhook webhook;
        volatile boolean connected = true;
        volatile boolean closed;

        FakeTransport(@Nullable ChannelWebhook webhook) { this.webhook = webhook; }

        @Override public boolean connected() { return connected && !closed; }

        @Override public Optional<ChannelWebhook> webhook() { return Optional.ofNullable(webhook); }

        @Override public void close() { closed = true; }
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
