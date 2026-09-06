package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembership;
import io.memoryos.iam.TenantMembershipRole;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaTenantAccessResolverTest {

    private JdbcClient jdbcClient;
    private JpaHarness jpa;
    private TenantAccessResolver resolver;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        resolver = TestDatabase.transactionalProxy(
                new JpaTenantAccessResolver(new JpaTenantRepository(jpa.entityManager())),
                TenantAccessResolver.class,
                jpa.transactionManager()
        );
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void resolvesActiveOwnerAndMemberMemberships() {
        ActorId owner = new ActorId(UUID.randomUUID());
        ActorId member = new ActorId(UUID.randomUUID());
        TenantId tenantId = new TenantId(UUID.randomUUID());
        persistActor(owner);
        persistActor(member);
        persistTenant(tenantId);
        persistMembership(owner, tenantId, TenantMembershipRole.OWNER, "ACTIVE");
        persistMembership(member, tenantId, TenantMembershipRole.MEMBER, "ACTIVE");

        assertEquals(
                new TenantMembership(tenantId, "Tasco", TenantMembershipRole.OWNER),
                resolver.findActiveMembership(owner).orElseThrow()
        );
        assertEquals(
                new TenantMembership(tenantId, "Tasco", TenantMembershipRole.MEMBER),
                resolver.findActiveMembership(member).orElseThrow()
        );
    }

    @Test
    void excludesInactiveMembershipAndInactiveTenant() {
        ActorId actor = new ActorId(UUID.randomUUID());
        TenantId tenantId = new TenantId(UUID.randomUUID());
        persistActor(actor);
        persistTenant(tenantId);
        persistMembership(actor, tenantId, TenantMembershipRole.OWNER, "INACTIVE");

        assertTrue(resolver.findActiveMembership(actor).isEmpty());
        jdbcClient.sql("""
                        UPDATE tenant_memberships
                        SET status = 'ACTIVE'
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", tenantId.value())
                .param("actorId", actor.value())
                .update();
        jdbcClient.sql("UPDATE tenants SET status = 'INACTIVE' WHERE id = :tenantId")
                .param("tenantId", tenantId.value())
                .update();
        assertTrue(resolver.findActiveMembership(actor).isEmpty());
        assertFalse(resolver.isActiveTenant(tenantId));
    }

    private void persistActor(ActorId actorId) {
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId.value())
                .update();
    }

    private void persistTenant(TenantId tenantId) {
        jdbcClient.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:tenantId, :slug, :displayName, 'ACTIVE', 'test')
                        """)
                .param("tenantId", tenantId.value())
                .param("slug", "tenant-" + tenantId.value().toString().substring(0, 8))
                .param("displayName", "Tasco")
                .update();
    }

    private void persistMembership(
            ActorId actorId,
            TenantId tenantId,
            TenantMembershipRole role,
            String status
    ) {
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, :status)
                        """)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("role", role.name())
                .param("status", status)
                .update();
    }
}
