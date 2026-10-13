package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
                INSERT INTO channel_connections(tenant_id, id, provider, name, region, app_id, secret_ciphertext, secret_nonce,
                                                secret_key_version, notice_language, enabled, service_account_id)
                VALUES (:tenant, :id, 'LARK', 'Bot', 'LARK', 'cli_a1b2c3d4e5f6', :secret, :nonce, :version, 'vi', TRUE, :account)
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

    @Test
    void aMessageIsAnsweredOffTheAdaptersThreadAndMarksTheConnection() {
        supervisor.start();
        while (adapter.receivers.isEmpty()) Thread.onSpinWait();
        var message = new ChannelMessage(connection, "m1", "oc_chat", ChannelMessage.Kind.DIRECT, "ou_ha", null, "org", "org", true,
                ChannelMessage.Type.TEXT, "Hello", null);
        adapter.receivers.getFirst().accept(message);
        verify(inbound, timeout(5000)).receive(message);
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

        @Override public ChannelProvider provider() { return ChannelProvider.LARK; }

        @Override
        public ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver) {
            if (failing) throw new IllegalStateException("provider refused");
            secrets.add(secret);
            receivers.add(receiver);
            var transport = new FakeTransport();
            opened.add(transport);
            return transport;
        }

        @Override public ChannelReply reply(ChannelConnection connection, ChannelMessage message) { throw new UnsupportedOperationException(); }

        @Override public void notice(ChannelConnection connection, ChannelMessage message, String text) {}

        @Override public Optional<String> senderEmail(ChannelConnection connection, String senderId) { return Optional.empty(); }

        @Override public boolean externalChat(ChannelConnection connection, String chatId) { return false; }
    }

    private static final class FakeTransport implements ChannelTransport {
        volatile boolean connected = true;
        volatile boolean closed;

        @Override public boolean connected() { return connected && !closed; }

        @Override public void close() { closed = true; }
    }
}
