package io.memoryos.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectRangeContent;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.UploadAuthorization;
import io.memoryos.objectstorage.UploadConstraints;
import io.memoryos.sandbox.persistence.JdbcSandboxRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.Sha256;
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

/** MEM-270: a conversation's sandbox found, renewed, replaced and bounded through its row (V179); its workspace archived (V184). */
@Testcontainers
class PostgresSandboxServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T08:00:00Z"));
    private final FakeRuntime runtime = new FakeRuntime(clock);
    private final FakeStorage storage = new FakeStorage();
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
    void aSlowStartHoldsNoLockThatOtherConversationsWaitFor() throws Exception {
        var service = service(properties(2));
        UUID first = conversation();
        UUID second = conversation();
        runtime.startDelay = Duration.ofMillis(1500);
        long began = System.nanoTime();
        Thread slow = Thread.ofVirtual().start(() -> service.acquire(tenant, owner, first));
        Thread.sleep(200);
        Thread other = Thread.ofVirtual().start(() -> service.acquire(tenant, owner, second));
        slow.join();
        other.join();
        // Run one after the other the two starts would take three seconds.
        assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofMillis(2500)) < 0);
        assertEquals(2, runtime.created.size());
    }

    @Test
    void aFailedStartGivesItsPlaceBack() {
        var service = service(properties(1));
        runtime.failNextCreate = true;
        assertEquals("SANDBOX_UNAVAILABLE",
                assertThrows(SandboxException.class, () -> service.acquire(tenant, owner, conversation())).code());
        service.acquire(tenant, owner, conversation());
        assertEquals(1, runtime.created.size());
    }

    @Test
    void twoCallsOfOneConversationShareOneStart() throws Exception {
        var service = service(properties(3));
        UUID conversation = conversation();
        runtime.startDelay = Duration.ofMillis(800);
        var ids = new ConcurrentLinkedQueue<String>();
        List<Thread> threads = new ArrayList<>();
        for (int call = 0; call < 2; call++)
            threads.add(Thread.ofVirtual().start(() -> ids.add(service.acquire(tenant, owner, conversation).runtimeId())));
        for (Thread thread : threads) thread.join();
        assertEquals(1, runtime.created.size());
        assertEquals(1, Set.copyOf(ids).size());
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
        var noRuntime = new SandboxService(new StaticListableBeanFactory().getBeanProvider(SandboxRuntime.class), storages(),
                workspaces, properties(3), transactions, clock);
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
        runtime.createdAt.put(orphan, clock.instant().minus(SandboxReconciliation.GRACE.plusMinutes(1)));

        reconciliation().sweep();

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

    @Test
    void aTurnsWorkspaceIsArchivedAndTheNextSandboxStartsFromIt() {
        var service = service(properties(3));
        UUID conversation = conversation();
        service.save(tenant, conversation);
        assertTrue(storage.objects.isEmpty(), "without a sandbox there is nothing to archive");

        var first = service.acquire(tenant, owner, conversation);
        service.save(tenant, conversation);
        assertEquals(1, storage.objects.size());
        String key = storage.objects.keySet().iterator().next();
        assertTrue(key.startsWith("raw/" + tenant.value() + "/"), key);
        assertEquals(key, archiveKey(conversation));

        runtime.alive.remove(first.runtimeId());
        var second = service.acquire(tenant, owner, conversation);
        assertEquals(List.of(WorkspaceArchives.INCOMING), runtime.writes.get(second.runtimeId()));
        assertTrue(runtime.commands(second.runtimeId()).contains(WorkspaceArchives.EXTRACT));
        assertTrue(!runtime.writes.containsKey(first.runtimeId()), "the first sandbox started empty");
    }

    @Test
    void aWorkspacePastTheBoundKeepsTheLastArchive() {
        var service = service(properties(3));
        UUID conversation = conversation();
        service.acquire(tenant, owner, conversation);
        service.save(tenant, conversation);
        String kept = archiveKey(conversation);
        runtime.archiveExit = 3;
        service.save(tenant, conversation);
        assertEquals(kept, archiveKey(conversation));
        assertEquals(1, storage.objects.size());
    }

    @Test
    void theReconciliationDeletesArchivesNoConversationStartsFrom() {
        var service = service(properties(3));
        UUID conversation = conversation();
        service.acquire(tenant, owner, conversation);
        service.save(tenant, conversation);
        service.save(tenant, conversation);
        assertEquals(2, storage.objects.size());

        reconciliation().sweep();
        assertEquals(2, storage.objects.size(), "an archive younger than the grace may still be recorded");

        clock.advance(WorkspaceArchives.SWEEP_GRACE.plusMinutes(1));
        reconciliation().sweep();
        assertEquals(Set.of(archiveKey(conversation)), storage.objects.keySet());

        new TransactionTemplate(transactions).executeWithoutResult(_ -> {
            jdbc.sql("DELETE FROM chat_message WHERE session_id = :id").param("id", conversation).update();
            jdbc.sql("DELETE FROM chat_session WHERE id = :id").param("id", conversation).update();
        });
        reconciliation().sweep();
        assertTrue(storage.objects.isEmpty(), "a deleted conversation's archive goes");
        assertEquals(0, jdbc.sql("SELECT count(*) FROM sandbox_workspace_archives").query(Integer.class).single());
    }

    private String archiveKey(UUID conversation) {
        return jdbc.sql("SELECT archive_key FROM sandbox_workspaces WHERE conversation_id = :id")
                .param("id", conversation).query(String.class).single();
    }

    private SandboxReconciliation reconciliation() {
        return new SandboxReconciliation(provider(), storages(), workspaces, properties(3), transactions, clock);
    }

    private SandboxService service(SandboxProperties properties) {
        return new SandboxService(provider(), storages(), workspaces, properties, transactions, clock);
    }

    private ObjectProvider<ObjectStorage> storages() {
        return new StaticListableBeanFactory(Map.of("storage", storage)).getBeanProvider(ObjectStorage.class);
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
        volatile boolean failNextCreate;
        final Clock clock;
        int renewals;
        String dieOnNextRun;
        volatile int archiveExit;
        final Map<String, List<String>> writes = new ConcurrentHashMap<>();
        final Map<String, List<String>> commands = new ConcurrentHashMap<>();

        List<String> commands(String id) {
            return commands.getOrDefault(id, List.of());
        }

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
            if (failNextCreate) {
                failNextCreate = false;
                throw new IllegalStateException("The runtime refused");
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
            commands.computeIfAbsent(id, _ -> Collections.synchronizedList(new ArrayList<>())).add(command.command());
            if (command.command().equals(WorkspaceArchives.TAKE))
                return new SandboxCommandResult(archiveExit,
                        archiveExit == 0 ? "12 " + Sha256.hex(new byte[12]) + "\n" : "600000000\n", "", false,
                        Duration.ofMillis(5));
            return new SandboxCommandResult(0, "ok", "", false, Duration.ofMillis(5));
        }

        @Override
        public void write(String id, String path, byte[] content) {
            writes.computeIfAbsent(id, _ -> Collections.synchronizedList(new ArrayList<>())).add(path);
        }

        @Override
        public byte[] read(String id, String path, long maxBytes) {
            return new byte[0];
        }

        @Override
        public InputStream open(String id, String path) {
            return new ByteArrayInputStream(path.equals(WorkspaceArchives.ARCHIVE) ? new byte[12] : new byte[0]);
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

    /** Object Storage as a map; a key is written once, as the S3 store's if-none-match write. */
    private static final class FakeStorage implements ObjectStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public void write(ObjectKey key, byte[] content, String mediaType) {
            if (objects.putIfAbsent(key.value(), content) != null)
                throw new ObjectStorageException(ObjectStorageFailureCode.PRECONDITION_FAILED, false, null);
        }

        @Override
        public UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectMetadata inspect(ObjectKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectContent open(ObjectKey key) {
            byte[] content = objects.get(key.value());
            if (content == null) throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, null);
            return new ObjectContent() {
                @Override
                public ObjectMetadata metadata() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public InputStream inputStream() {
                    return new ByteArrayInputStream(content);
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public ObjectRangeContent openRange(ObjectKey key, long first, long last) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ObjectKey key) {
            if (objects.remove(key.value()) == null)
                throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, null);
        }
    }
}
