package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.GroupProvisioner;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapResult;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.iam.TenantBootstrapConflictException;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.persistence.GroupMembershipRepository;
import io.memoryos.iam.persistence.GroupRepository;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.persistence.JpaTenantRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DefaultInitialTenantBootstrapperTest {

    private static final TenantId TENANT_ID = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000023")
    );
    private static final ExternalIdentity OWNER_IDENTITY = new ExternalIdentity(
            "https://keycloak.example/realms/memoryos",
            "tasco-owner"
    );

    private JdbcClient jdbcClient;
    private JpaHarness jpa;
    private JpaTenantRepository tenants;
    private JpaExternalIdentityRegistry identities;
    private IamLockRepository locks;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        tenants = new JpaTenantRepository(jpa.entityManager());
        identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        locks = new IamLockRepository(jdbcClient);
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void createsTheExactInitialIamAggregateAndReplaysTheSameConfiguration() {
        InitialTenantBootstrapper bootstrapper = bootstrapper(groupProvisioner());

        InitialTenantBootstrapResult created = bootstrapper.bootstrap(request());
        InitialTenantBootstrapResult replayed = bootstrapper.bootstrap(request());

        assertTrue(created.created());
        assertFalse(replayed.created());
        assertEquals(created.ownerActorId(), replayed.ownerActorId());
        assertEquals(TENANT_ID, created.tenantId());
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(1L, count("tenants"));
        assertEquals(1L, count("tenant_memberships"));
        assertEquals(2L, count("iam_groups"));
        assertEquals(2L, count("iam_group_memberships"));
        assertEquals(1L, count("iam_group_capability_grants"));
        assertEquals("STANDARD", jdbcClient.sql("SELECT account_type FROM actors").query(String.class).single());
        assertEquals(
                TENANT_ID.value(),
                jdbcClient.sql("SELECT tenant_id FROM tenant_bootstrap_state WHERE id = 1")
                        .query(UUID.class)
                        .single()
        );
    }

    @Test
    void serializesConcurrentStartupAndCreatesOneAggregate() throws Exception {
        InitialTenantBootstrapper bootstrapper = bootstrapper(groupProvisioner());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return bootstrapper.bootstrap(request());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return bootstrapper.bootstrap(request());
            });
            ready.await();
            start.countDown();

            List<Boolean> creationResults = List.of(first.get().created(), second.get().created());
            assertEquals(1L, creationResults.stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("tenants"));
        assertEquals(1L, count("tenant_memberships"));
    }

    @Test
    void rejectsConfigurationDriftWithoutChangingTheExistingAggregate() {
        InitialTenantBootstrapper bootstrapper = bootstrapper(groupProvisioner());
        InitialTenantBootstrapResult initial = bootstrapper.bootstrap(request());
        InitialTenantBootstrapRequest changed = new InitialTenantBootstrapRequest(
                TENANT_ID,
                OWNER_IDENTITY,
                "tasco",
                "Changed Tenant",
                "MEM-36"
        );

        assertThrows(TenantBootstrapConflictException.class, () -> bootstrapper.bootstrap(changed));
        assertEquals(initial.ownerActorId().value(), jdbcClient.sql("SELECT actor_id FROM tenant_memberships")
                .query(UUID.class)
                .single());
        assertEquals("Tasco AI", jdbcClient.sql("SELECT display_name FROM tenants").query(String.class).single());
    }

    @Test
    void rollsBackMixedJpaWritesWhenGroupProvisioningFails() {
        GroupProvisioner failingProvisioner = new GroupProvisioner() {
            @Override
            public void bootstrap(TenantId tenantId, ActorId configuredOwner) {
                throw new IllegalStateException("group bootstrap failed");
            }

            @Override
            public void addToBasicGroup(TenantId tenantId, ActorId actorId) {
                throw new UnsupportedOperationException();
            }
        };
        InitialTenantBootstrapper bootstrapper = bootstrapper(failingProvisioner);

        assertThrows(IllegalStateException.class, () -> bootstrapper.bootstrap(request()));
        assertEquals(0L, count("actors"));
        assertEquals(0L, count("external_identity_bindings"));
        assertEquals(0L, count("tenants"));
        assertEquals(0L, count("tenant_memberships"));
        assertEquals(0L, count("iam_groups"));
    }

    private InitialTenantBootstrapper bootstrapper(GroupProvisioner groupProvisioner) {
        return TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(
                        tenants,
                        locks,
                        identities,
                        identities,
                        groupProvisioner
                ),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        );
    }

    private GroupProvisioner groupProvisioner() {
        return new DefaultGroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private static InitialTenantBootstrapRequest request() {
        return new InitialTenantBootstrapRequest(
                TENANT_ID,
                OWNER_IDENTITY,
                "tasco",
                "Tasco AI",
                "MEM-36"
        );
    }
}
