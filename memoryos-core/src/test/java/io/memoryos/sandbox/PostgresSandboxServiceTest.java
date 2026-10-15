package io.memoryos.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.sandbox.persistence.JdbcSandboxRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** MEM-270: a conversation's sandbox is found, renewed, replaced and bounded through its row (V174). */
@Testcontainers
class PostgresSandboxServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T08:00:00Z"));
    private final FakeRuntime runtime = new FakeRuntime(clock);
    private JdbcClient jdbc;
    private JdbcSandboxRepository workspaces;
    private DataSourceTransactionManager transactions;
    private TenantId tenant;
    private ActorId owner;
    private UUID persona;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        workspaces = new JdbcSandboxRepository(jdbc);
        transactions = new DataSourceTransactionManager(dataSource);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Sandbox','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,name,instructions,builtin_key) VALUES(:id,:tenant,'Default','','default')")
                .param("id", persona).param("tenant", tenant.value()).update();
    }

    @Test
    void aConversationKeepsOneSandboxRenewedOnEveryUse() {
        var service = service(properties(3));
        UUID conversation = conversation();
        var first = service.acquire(tenant, owner, conversation);
        clock.advance(Duration.ofMinutes(10));
        var second = service.acquire(tenant, owner, conversation);
        assertEquals(first.runtimeId(), second.runtimeId());
        assertEquals(1, runtime.created.size());
        assertEquals(1, runtime.renewals);
    }

    @Test
    void aSandboxTheRuntimeNoLongerHasIsReplaced() {
        var service = service(properties(3));
        UUID conversation = conversation();
        var first = service.acquire(tenant, owner, conversation);
        runtime.alive.remove(first.runtimeId());
        var second = service.acquire(tenant, owner, conversation);
        assertNotEquals(first.runtimeId(), second.runtimeId());
        assertEquals(2, runtime.created.size());
    }

    @Test
    void aSandboxPastItsLifetimeIsKilledAndReplaced() {
        var service = service(properties(3));
        UUID conversation = conversation();
        var first = service.acquire(tenant, owner, conversation);
        clock.advance(Duration.ofHours(4).plusMinutes(1));
        var second = service.acquire(tenant, owner, conversation);
        assertNotEquals(first.runtimeId(), second.runtimeId());
        assertTrue(runtime.killed.contains(first.runtimeId()));
    }

    @Test
    void pastTheBoundAnotherConversationIsToldTheSandboxesAreBusy() {
        var service = service(properties(1));
        service.acquire(tenant, owner, conversation());
        var busy = assertThrows(SandboxException.class, () -> service.acquire(tenant, owner, conversation()));
        assertEquals("SANDBOX_BUSY", busy.code());
        // An idle sandbox no longer counts against the bound.
        clock.advance(Duration.ofMinutes(31));
        service.acquire(tenant, owner, conversation());
    }

    @Test
    void twoConversationsStartingAtOnceCannotBothTakeTheLastPlace() throws Exception {
        var service = service(properties(1));
        UUID first = conversation();
        UUID second = conversation();
        runtime.startDelay = Duration.ofMillis(500);
        var start = new CountDownLatch(1);
        var outcomes = new ConcurrentLinkedQueue<String>();
        List<Thread> threads = new ArrayList<>();
        for (UUID conversation : List.of(first, second)) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    service.acquire(tenant, owner, conversation);
                    outcomes.add("started");
                } catch (SandboxException busy) {
                    outcomes.add(busy.code());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertEquals(1, runtime.created.size());
        assertTrue(outcomes.contains("started") && outcomes.contains("SANDBOX_BUSY"), outcomes.toString());
    }

    @Test
    void aCommandRunsAgainInAFreshSandboxWhenItsOwnWentAwayMidCall() {
        var service = service(properties(3));
        UUID conversation = conversation();
        var first = service.acquire(tenant, owner, conversation);
        runtime.dieOnNextRun = first.runtimeId();
        var result = service.run(tenant, owner, conversation,
                new SandboxCommand("echo ok", SandboxService.WORKSPACE, SandboxService.USER, Duration.ofSeconds(60)));
        assertEquals("ok", result.stdout());
        assertEquals(2, runtime.created.size());
    }

    @Test
    void withoutAnImageOrARuntimeThereIsNoSandbox() {
        var unconfigured = service(new SandboxProperties("", Duration.ofMinutes(30), Duration.ofHours(4), 3, "1", "1Gi", null));
        assertEquals("SANDBOX_NOT_CONFIGURED",
                assertThrows(SandboxException.class, () -> unconfigured.acquire(tenant, owner, conversation())).code());
        var noRuntime = new SandboxService(new StaticListableBeanFactory().getBeanProvider(SandboxRuntime.class), workspaces,
                properties(3), transactions, clock);
        assertEquals("SANDBOX_NOT_CONFIGURED",
                assertThrows(SandboxException.class, () -> noRuntime.acquire(tenant, owner, conversation())).code());
    }

    @Test
    void theReconciliationKillsOrphansAndClearsRowsWhoseSandboxIsGone() {
        var service = service(properties(3));
        UUID kept = conversation();
        UUID lost = conversation();
        var alive = service.acquire(tenant, owner, kept);
        var gone = service.acquire(tenant, owner, lost);
        runtime.alive.remove(gone.runtimeId());
        String orphan = runtime.create(spec());
        String young = runtime.create(spec());
        runtime.createdAt.put(orphan, clock.instant().minus(Duration.ofMinutes(5)));

        new SandboxReconciliation(provider(), workspaces, properties(3), clock).sweep();

        assertTrue(runtime.killed.contains(orphan));
        assertTrue(!runtime.killed.contains(young), "a sandbox younger than the grace may await its row");
        assertTrue(!runtime.killed.contains(alive.runtimeId()));
        assertEquals(1, workspaces.recorded(FakeRuntime.NAME).size());
        assertEquals(alive.runtimeId(), workspaces.recorded(FakeRuntime.NAME).getFirst().runtimeId());
    }

    @Test
    void deletingTheConversationDeletesItsRow() {
        var service = service(properties(3));
        UUID conversation = conversation();
        service.acquire(tenant, owner, conversation);
        new TransactionTemplate(transactions).executeWithoutResult(_ -> {
            jdbc.sql("DELETE FROM chat_message WHERE session_id = :id").param("id", conversation).update();
            jdbc.sql("DELETE FROM chat_session WHERE id = :id").param("id", conversation).update();
        });
        assertEquals(0, workspaces.recorded(FakeRuntime.NAME).size());
    }

    private SandboxService service(SandboxProperties properties) {
        return new SandboxService(provider(), workspaces, properties, transactions, clock);
    }

    private ObjectProvider<SandboxRuntime> provider() {
        return new StaticListableBeanFactory(Map.of("runtime", runtime)).getBeanProvider(SandboxRuntime.class);
    }

    private static SandboxProperties properties(int maxRunning) {
        return new SandboxProperties("memoryos-sandbox-computer:test", Duration.ofMinutes(30), Duration.ofHours(4),
                maxRunning, "1", "1Gi", List.of());
    }

    private static SandboxSpec spec() {
        return new SandboxSpec("image", Duration.ofMinutes(30), "1", "1Gi", List.of(), Map.of(SandboxService.MANAGED, "true"));
    }

    /** A conversation of {@code owner}; chat_session and its root message reference each other, so one transaction. */
    private UUID conversation() {
        UUID session = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(_ -> {
            jdbc.sql("""
                    INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title)
                    VALUES(:id,:tenant,:actor,:persona,:root,'Sandbox')
                    """).param("id", session).param("tenant", tenant.value()).param("actor", owner.value())
                    .param("persona", persona).param("root", root).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,role,status,finished_at)
                    VALUES(:id,:session,'ROOT','COMPLETED',CURRENT_TIMESTAMP)
                    """).param("id", root).param("session", session).update();
        });
        return session;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeRuntime implements SandboxRuntime {
        static final String NAME = "OPENSANDBOX";
        final List<String> created = Collections.synchronizedList(new ArrayList<>());
        final List<String> killed = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Instant> createdAt = new ConcurrentHashMap<>();
        final Set<String> alive = ConcurrentHashMap.newKeySet();
        volatile Duration startDelay = Duration.ZERO;
        final Clock clock;
        int renewals;
        String dieOnNextRun;

        FakeRuntime(Clock clock) {
            this.clock = clock;
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public String create(SandboxSpec spec) {
            try {
                Thread.sleep(startDelay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            String id = "sbx-" + UUID.randomUUID();
            created.add(id);
            alive.add(id);
            createdAt.put(id, clock.instant());
            return id;
        }

        @Override
        public void renew(String id, Duration timeToLive) {
            if (!alive.contains(id)) throw new SandboxGoneException(id, new IllegalStateException("gone"));
            renewals++;
        }

        @Override
        public SandboxCommandResult run(String id, SandboxCommand command) {
            if (id.equals(dieOnNextRun)) {
                dieOnNextRun = null;
                alive.remove(id);
                throw new SandboxGoneException(id, new IllegalStateException("gone"));
            }
            return new SandboxCommandResult(0, "ok", "", false, Duration.ofMillis(5));
        }

        @Override
        public void write(String id, String path, byte[] content) {}

        @Override
        public byte[] read(String id, String path, long maxBytes) {
            return new byte[0];
        }

        @Override
        public InputStream open(String id, String path) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public List<SandboxEntry> list(String id, String path, int depth) {
            return List.of();
        }

        @Override
        public void kill(String id) {
            killed.add(id);
            alive.remove(id);
        }

        @Override
        public Map<String, Instant> running(Map<String, String> labels) {
            Map<String, Instant> running = new HashMap<>();
            for (String id : alive) running.put(id, createdAt.get(id));
            return running;
        }
    }
}
