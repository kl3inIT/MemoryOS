package io.memoryos.iam.identity;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.TrustedIdentityAdmission;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.group.GroupProvisioner;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.tenant.persistence.JpaTenantMembershipProvisioner;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.tenant.DefaultInitialTenantBootstrapper;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DefaultTrustedIdentityAdmissionTest {

    private static final TenantId TENANT = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000059"));
    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final ExternalIdentity IDENTITY = new ExternalIdentity(ISSUER, "tasco-member");

    private JpaHarness jpa;
    private JdbcClient jdbc;
    private JpaTenantRepository tenants;
    private JpaExternalIdentityRegistry identities;
    private IamLockRepository locks;
    private GroupProvisioner groups;
    private TrustedIdentityAdmission admission;
    private ActorId owner;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        tenants = new JpaTenantRepository(jpa.entityManager());
        identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        locks = new IamLockRepository(jdbc);
        groups = new GroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        var bootstrapper = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(tenants, locks, identities, identities, groups, event -> { }),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        );
        owner = bootstrapper.bootstrap(new InitialTenantBootstrapRequest(
                TENANT, new ExternalIdentity(ISSUER, "owner"), "tasco", "Tasco", "TEST-MEM-59"
        )).ownerActorId();
        admission = service(groups);
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void admitsStandardMemberWithOnlyNonManagerBasicAndReplaysWithoutChangingAuthority() {
        ActorId actor = admission.admit(TENANT, IDENTITY);
        assertEquals(actor, admission.admit(TENANT, IDENTITY));
        assertEquals(actor, identities.resolve(IDENTITY).orElseThrow());
        assertEquals("STANDARD", jdbc.sql("SELECT account_type FROM actors WHERE id = :actor")
                .param("actor", actor.value()).query(String.class).single());
        assertEquals("MEMBER:ACTIVE", jdbc.sql("SELECT role || ':' || status FROM tenant_memberships WHERE actor_id = :actor")
                .param("actor", actor.value()).query(String.class).single());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM iam_group_memberships WHERE actor_id = :actor")
                .param("actor", actor.value()).query(Long.class).single());
        assertEquals("BASIC:false", jdbc.sql("""
                        SELECT g.system_key || ':' || gm.is_manager
                        FROM iam_group_memberships gm JOIN iam_groups g
                          ON g.tenant_id = gm.tenant_id AND g.id = gm.group_id
                        WHERE gm.actor_id = :actor
                        """)
                .param("actor", actor.value()).query(String.class).single());
        var authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks);
        assertEquals(
                Set.of(IamCapability.SYSTEM_BASIC, IamCapability.SEARCH_READ,
                        IamCapability.CHAT_READ, IamCapability.CHAT_WRITE,
                        IamCapability.IMAGE_GENERATE, IamCapability.LLM_GATEWAY_USE),
                authorization.effectiveCapabilities(actor)
        );
        assertEquals(Set.of(), authorization.scopedCapabilities(actor));
        assertEquals(owner, admission.admit(TENANT, new ExternalIdentity(ISSUER, "owner")));
        assertEquals("OWNER", jdbc.sql("SELECT role FROM tenant_memberships WHERE actor_id = :actor")
                .param("actor", owner.value()).query(String.class).single());
        assertEquals(2L, jdbc.sql("SELECT COUNT(*) FROM iam_group_memberships WHERE actor_id = :actor")
                .param("actor", owner.value()).query(Long.class).single());
        assertEquals(2L, count("actors"));
        assertEquals(0L, count("tenant_invitations"));
        // One admission, recorded once: the replay and the owner's sign-in change no authority.
        assertEquals(java.util.List.of("auth.jit_admit"), jdbc.sql("SELECT action FROM audit_event").query(String.class).list());
    }

    @Test
    void reusesOnlyTheExactBindingWithoutCreatingAnotherActor() {
        ActorId actor = new TransactionTemplate(jpa.transactionManager())
                .execute(_ -> identities.resolveOrCreate(IDENTITY));
        assertEquals(actor, admission.admit(TENANT, IDENTITY));
        ActorId otherIssuer = admission.admit(TENANT, new ExternalIdentity("https://other.example/realms/memoryos", IDENTITY.subject()));
        assertTrue(!actor.equals(otherIssuer));
        assertEquals(3L, count("actors"));
    }

    @Test
    void refusesToReactivateAnInactiveMembership() {
        ActorId actor = admission.admit(TENANT, IDENTITY);
        jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor")
                .param("actor", actor.value()).update();
        var failure = assertThrows(IamException.class, () -> admission.admit(TENANT, IDENTITY));
        assertEquals(IamFailureReason.ACCESS_DENIED.code(), failure.code());
        assertEquals("INACTIVE", jdbc.sql("SELECT status FROM tenant_memberships WHERE actor_id = :actor")
                .param("actor", actor.value()).query(String.class).single());
        assertEquals(2L, count("actors"));
    }

    @Test
    void rejectsInactiveOrUnconfiguredTenantWithoutCreatingIdentity() {
        jdbc.sql("UPDATE tenants SET status = 'INACTIVE' WHERE id = :tenant").param("tenant", TENANT.value()).update();
        assertEquals(IamFailureReason.ACCESS_DENIED.code(),
                assertThrows(IamException.class, () -> admission.admit(TENANT, IDENTITY)).code());
        assertEquals(IamFailureReason.ACCESS_DENIED.code(),
                assertThrows(IamException.class, () -> admission.admit(new TenantId(UUID.randomUUID()), IDENTITY)).code());
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(1L, count("tenant_memberships"));
    }

    @Test
    void rollsBackActorBindingMembershipAndBasicEdgeWhenAdmissionFailsAfterProvisioning() {
        var failing = service(wrappingGroups(() -> { throw new IllegalStateException("group failure"); }));
        assertThrows(IllegalStateException.class, () -> failing.admit(TENANT, IDENTITY));
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(1L, count("tenant_memberships"));
        assertEquals(2L, count("iam_group_memberships"));
        assertEquals(0L, jdbc.sql("SELECT authorization_version FROM tenants WHERE id = :tenant")
                .param("tenant", TENANT.value()).query(Long.class).single());
    }

    @Test
    void concurrentAdmissionWaitsForTenantAndReturnsTheSameCommittedActor() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstAdmission = service(wrappingGroups(() -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> firstAdmission.admit(TENANT, IDENTITY));
            try {
                assertTrue(entered.await(10, SECONDS));
                var second = executor.submit(() -> admission.admit(TENANT, IDENTITY));
                assertThrows(TimeoutException.class, () -> second.get(250, MILLISECONDS));
                release.countDown();
                assertEquals(first.get(10, SECONDS), second.get(10, SECONDS));
                assertEquals(2L, count("actors"));
                assertEquals(2L, count("external_identity_bindings"));
                assertEquals(2L, count("tenant_memberships"));
                assertEquals(3L, count("iam_group_memberships"));
            } finally {
                release.countDown();
            }
        }
    }

    private TrustedIdentityAdmission service(GroupProvisioner provisioner) {
        return TestDatabase.transactionalProxy(
                new DefaultTrustedIdentityAdmission(tenants, locks, identities,
                        new JpaTenantMembershipProvisioner(tenants), provisioner,
                        TestDatabase.audit(jdbc, jpa.transactionManager())),
                TrustedIdentityAdmission.class,
                jpa.transactionManager()
        );
    }

    private GroupProvisioner wrappingGroups(Runnable afterWrite) {
        GroupProvisioner wrapping = org.mockito.Mockito.mock(GroupProvisioner.class);
        org.mockito.Mockito.doAnswer(call -> {
            groups.bootstrap(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(wrapping).bootstrap(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
            groups.addToBasicGroup(call.getArgument(0), call.getArgument(1));
            afterWrite.run();
            return null;
        }).when(wrapping).addToBasicGroup(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return wrapping;
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
