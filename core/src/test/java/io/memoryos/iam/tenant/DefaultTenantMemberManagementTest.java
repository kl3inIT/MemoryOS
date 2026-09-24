package io.memoryos.iam.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.identity.ExternalIdentity;
import io.memoryos.iam.IamException;
import io.memoryos.iam.tenant.bootstrap.InitialTenantBootstrapRequest;
import io.memoryos.iam.tenant.bootstrap.InitialTenantBootstrapper;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.memoryos.iam.group.DefaultGroupAdministrationGuard;
import io.memoryos.iam.group.DefaultGroupProvisioner;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.tenant.bootstrap.DefaultInitialTenantBootstrapper;

// SQL is exercised against the isolated, migrated Testcontainers database.
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Testcontainers
class DefaultTenantMemberManagementTest {

    private static final TenantId TENANT_ID = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000023")
    );
    private static final ActorId MEMBER = new ActorId(
            UUID.fromString("20000000-0000-0000-0000-000000000023")
    );

    private JdbcClient jdbcClient;
    private JpaHarness jpa;
    private HikariDataSource dataSource;
    private ActorId owner;
    private TenantMemberManagement memberManagement;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        var tenants = new JpaTenantRepository(jpa.entityManager());
        var identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        var locks = new IamLockRepository(jdbcClient);
        var groupProvisioner = new DefaultGroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        InitialTenantBootstrapper bootstrapper = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(
                        tenants,
                        locks,
                        identities,
                        identities,
                        groupProvisioner,
                        event -> { }
                ),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        );
        owner = bootstrapper.bootstrap(new InitialTenantBootstrapRequest(
                TENANT_ID,
                new ExternalIdentity("https://issuer.example", "owner"),
                "tasco",
                "Tasco",
                "MEM-36"
        )).ownerActorId();
        persistMember();

        var authorization = new DefaultIamAuthorization(
                new IamAuthorizationRepository(jdbcClient),
                locks
        );
        var guard = new DefaultGroupAdministrationGuard(new GroupInvariantRepository(jdbcClient));
        memberManagement = TestDatabase.transactionalProxy(
                new DefaultTenantMemberManagement(
                        authorization,
                        tenants,
                        guard,
                        TestDatabase.audit(jdbcClient, jpa.transactionManager()),
                        Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC)
                ),
                TenantMemberManagement.class,
                jpa.transactionManager()
        );
    }

    @AfterEach
    void closeDatabase() {
        try {
            if (jpa != null) {
                jpa.close();
            }
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @Test
    void transitionsExistingMemberIdempotentlyWithoutReplacingMembershipOrGroups() {
        Instant createdAt = jdbcClient.sql("""
                        SELECT created_at FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", MEMBER.value())
                .query(Instant.class)
                .single();

        memberManagement.deactivate(owner, MEMBER);
        memberManagement.deactivate(owner, MEMBER);
        assertEquals("INACTIVE", membershipStatus());
        assertEquals(1L, membershipCount());
        assertEquals(1L, groupMembershipCount());
        assertEquals(createdAt, membershipCreatedAt());

        memberManagement.activate(owner, MEMBER);
        memberManagement.activate(owner, MEMBER);
        assertEquals("ACTIVE", membershipStatus());
        assertEquals(1L, membershipCount());
        assertEquals(1L, groupMembershipCount());
        assertEquals(createdAt, membershipCreatedAt());
        // A transition that changed nothing is not evidence of anything: one event each way.
        // Two events written in the same instant can tie on occurred_at, so the count is what matters here.
        assertEquals(java.util.List.of("user.deactivate", "user.reactivate"), jdbcClient.sql("""
                        SELECT action FROM audit_event WHERE tenant_id = :tenantId AND resource_id = :actorId ORDER BY action
                        """).param("tenantId", TENANT_ID.value()).param("actorId", MEMBER.value().toString())
                .query(String.class).list());
    }

    @Test
    void protectsConfiguredOwnerEvenWhenRequestedStatusAlreadyMatches() {
        TenantMemberException activateFailure = assertThrows(
                TenantMemberException.class,
                () -> memberManagement.activate(owner, owner)
        );
        TenantMemberException deactivateFailure = assertThrows(
                TenantMemberException.class,
                () -> memberManagement.deactivate(owner, owner)
        );

        assertEquals("TENANT_MEMBER_OWNER_PROTECTED", activateFailure.code());
        assertEquals("TENANT_MEMBER_OWNER_PROTECTED", deactivateFailure.code());
        assertEquals("ACTIVE", jdbcClient.sql("""
                        SELECT status FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", owner.value())
                .query(String.class)
                .single());
    }

    @Test
    void requiresCurrentUsersManageCapability() {
        assertThrows(IamException.class, () -> memberManagement.deactivate(MEMBER, MEMBER));
        assertEquals("ACTIVE", membershipStatus());
        assertEquals(0L, jdbcClient.sql("SELECT count(*) FROM audit_event").query(Long.class).single());
    }

    private void persistMember() {
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", MEMBER.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", MEMBER.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
                        VALUES (:tenantId, :groupId, :actorId)
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("groupId", UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .param("actorId", MEMBER.value())
                .update();
    }

    private String membershipStatus() {
        return jdbcClient.sql("""
                        SELECT status FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", MEMBER.value())
                .query(String.class)
                .single();
    }

    private Instant membershipCreatedAt() {
        return jdbcClient.sql("""
                        SELECT created_at FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", MEMBER.value())
                .query(Instant.class)
                .single();
    }

    private long membershipCount() {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", MEMBER.value())
                .query(Long.class)
                .single();
    }

    private long groupMembershipCount() {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM iam_group_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", MEMBER.value())
                .query(Long.class)
                .single();
    }
}
