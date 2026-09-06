package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupProjectionRepository;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresIamAuthorizationTest {
    private static final TenantId TENANT = new TenantId(
            uuid("10000000-0000-0000-0000-000000000036")
    );
    private static final ActorId ACTOR = new ActorId(
            uuid("20000000-0000-0000-0000-000000000036")
    );
    private static final UUID GROUP_ONE = uuid("30000000-0000-0000-0000-000000000036");
    private static final UUID GROUP_TWO = uuid("40000000-0000-0000-0000-000000000036");

    private JdbcClient jdbc;
    private TransactionTemplate transaction;
    private IamLockRepository locks;
    private DefaultIamAuthorization authorization;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        locks = new IamLockRepository(jdbc);
        authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks);
        executor = Executors.newFixedThreadPool(2);

        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot
                        ) VALUES (:id, 'authorization-test', 'Authorization test', 'ACTIVE', 'test', 1)
                        """)
                .param("id", TENANT.value())
                .update();
        jdbc.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", ACTOR.value())
                .update();
        jdbc.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", ACTOR.value())
                .update();
        persistGroup(GROUP_ONE, "One");
        persistGroup(GROUP_TWO, "Two");
        addMembership(GROUP_ONE);
        addMembership(GROUP_TWO);
    }

    @AfterEach
    void closeExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void unionsFreshGrantsAndExpandsImplicationsCentrally() {
        grant(GROUP_ONE, IamCapability.GROUPS_MANAGE);
        grant(GROUP_TWO, IamCapability.SOURCES_MANAGE);

        assertEquals(
                Set.of(
                        IamCapability.GROUPS_MANAGE,
                        IamCapability.GROUPS_READ,
                        IamCapability.SOURCES_MANAGE,
                        IamCapability.SOURCES_READ
                ),
                authorization.effectiveCapabilities(ACTOR)
        );
        assertEquals(
                Authority.GLOBAL,
                authorization.require(ACTOR, IamCapability.GROUPS_READ, false).authority()
        );
        assertEquals(Set.of(), authorization.scopedCapabilities(ACTOR));

        jdbc.sql("""
                        DELETE FROM iam_group_capability_grants
                        WHERE tenant_id = :tenantId AND group_id = :groupId
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", GROUP_ONE)
                .update();

        assertThrows(
                IamException.class,
                () -> authorization.require(ACTOR, IamCapability.GROUPS_READ, false)
        );
    }

    @Test
    void derivesScopedAuthorityOnlyFromActiveOrdinaryGroupManagement() {
        jdbc.sql("""
                        UPDATE iam_group_memberships
                        SET is_manager = TRUE
                        WHERE tenant_id = :tenantId AND group_id = :groupId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", GROUP_ONE)
                .param("actorId", ACTOR.value())
                .update();

        assertEquals(Set.of(), authorization.effectiveCapabilities(ACTOR));
        assertEquals(
                Set.of(
                        IamCapability.GROUPS_READ,
                        IamCapability.GROUPS_MANAGE,
                        IamCapability.SOURCES_READ,
                        IamCapability.SOURCES_MANAGE
                ),
                authorization.scopedCapabilities(ACTOR)
        );
        assertEquals(
                Authority.SCOPED,
                authorization.require(ACTOR, IamCapability.SOURCES_MANAGE, true).authority()
        );
        assertThrows(
                IamException.class,
                () -> authorization.require(ACTOR, IamCapability.USERS_MANAGE, true)
        );

        jdbc.sql("""
                        UPDATE tenant_memberships
                        SET status = 'INACTIVE'
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", ACTOR.value())
                .update();

        assertEquals(Set.of(), authorization.scopedCapabilities(ACTOR));
        assertThrows(
                IamException.class,
                () -> authorization.require(ACTOR, IamCapability.GROUPS_READ, true)
        );
    }

    @Test
    void scopedGroupProjectionReturnsOnlyTheManagersOwnOrdinaryGroups() {
        jdbc.sql("""
                        UPDATE iam_group_memberships
                        SET is_manager = TRUE
                        WHERE tenant_id = :tenantId AND group_id = :groupId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", GROUP_ONE)
                .param("actorId", ACTOR.value())
                .update();
        var projections = new GroupProjectionRepository(jdbc);

        var page = projections.list(TENANT, ACTOR, false, new GroupQuery(null, 0, 100));

        assertEquals(1, page.totalItems());
        assertEquals(
                Set.of(new GroupId(GROUP_ONE)),
                page.items().stream().map(GroupProjectionRepository.GroupRecord::id).collect(
                        java.util.stream.Collectors.toSet()
                )
        );
        assertEquals(
                java.util.Optional.empty(),
                projections.detail(TENANT, ACTOR, new GroupId(GROUP_TWO), false)
        );
    }

    @Test
    void rejectsReservedOrBasicGroupGrantsAtTheDatabaseBoundary() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> grant(GROUP_ONE, IamCapability.IAM_ADMIN)
        );
        assertEquals(Set.of(), authorization.effectiveCapabilities(ACTOR));

        jdbc.sql("""
                        UPDATE iam_groups
                        SET system_key = 'BASIC'
                        WHERE tenant_id = :tenantId AND id = :groupId
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", GROUP_ONE)
                .update();
        assertThrows(
                DataIntegrityViolationException.class,
                () -> grant(GROUP_ONE, IamCapability.SOURCES_MANAGE)
        );
        assertEquals(Set.of(), authorization.effectiveCapabilities(ACTOR));
    }

    @Test
    void advancesAuthorizationVersionOnlyWhenTheExclusiveMutationCommits() {
        assertEquals(0, authorization.authorizationVersion(ACTOR));
        transaction.executeWithoutResult(_ -> locks.lockTenant(TENANT));
        assertEquals(1, authorization.authorizationVersion(ACTOR));

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(_ -> {
            locks.lockTenant(TENANT);
            throw new IllegalStateException("roll back");
        }));
        assertEquals(1, authorization.authorizationVersion(ACTOR));
    }

    @Test
    void sharedResourceWriteSerializesAConcurrentRevokeAndNextRequestFailsClosed() throws Exception {
        grant(GROUP_ONE, IamCapability.SOURCES_MANAGE);
        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch revokerAttempted = new CountDownLatch(1);
        AtomicLong writerBodyFinished = new AtomicLong();
        AtomicLong revokerAcquired = new AtomicLong();

        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(_ -> {
            authorization.lockAndRequire(ACTOR, IamCapability.SOURCES_MANAGE, false);
            writerLocked.countDown();
            await(revokerAttempted);
            sleepBriefly();
            writerBodyFinished.set(System.nanoTime());
        }), executor);
        assertTrue(writerLocked.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> revoker = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(_ -> {
            revokerAttempted.countDown();
            locks.lockTenant(TENANT);
            revokerAcquired.set(System.nanoTime());
            jdbc.sql("""
                            DELETE FROM iam_group_capability_grants
                            WHERE tenant_id = :tenantId AND group_id = :groupId
                            """)
                    .param("tenantId", TENANT.value())
                    .param("groupId", GROUP_ONE)
                    .update();
        }), executor);

        writer.get(10, TimeUnit.SECONDS);
        revoker.get(10, TimeUnit.SECONDS);
        assertTrue(revokerAcquired.get() > writerBodyFinished.get());
        assertThrows(
                IamException.class,
                () -> authorization.require(ACTOR, IamCapability.SOURCES_MANAGE, false)
        );
    }

    private void persistGroup(UUID groupId, String name) {
        jdbc.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name)
                        VALUES (:tenantId, :groupId, :name)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId)
                .param("name", name)
                .update();
    }

    private void addMembership(UUID groupId) {
        jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id, is_manager)
                        VALUES (:tenantId, :groupId, :actorId, FALSE)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId)
                .param("actorId", ACTOR.value())
                .update();
    }

    private void grant(UUID groupId, IamCapability capability) {
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, :capability)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId)
                .param("capability", capability.name())
                .update();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent transaction did not reach the lock");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
