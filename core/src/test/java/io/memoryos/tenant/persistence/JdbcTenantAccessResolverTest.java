package io.memoryos.tenant.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.TenantMembershipRole;
import io.memoryos.tenant.TenantSessionAuthority;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class JdbcTenantAccessResolverTest {

    private JdbcClient jdbcClient;
    private Connection keepAlive;
    private JdbcTenantAccessResolver resolver;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        try {
            keepAlive = dataSource.getConnection();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to keep the in-memory database open", exception);
        }
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql"),
                new ClassPathResource("db/migration/V5__create_file_source_and_document_schema.sql"),
                new ClassPathResource("db/migration/V6__cut_over_organization_to_tenant.sql")
        ).populate(keepAlive);
        jdbcClient = JdbcClient.create(dataSource);
        resolver = new JdbcTenantAccessResolver(jdbcClient);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keepAlive.close();
    }

    @Test
    void resolvesActiveOwnerAuthority() {
        var actorId = actor();
        var tenantId = tenant();
        persistAuthority(actorId, tenantId, TenantMembershipRole.OWNER);

        assertEquals(
                new TenantSessionAuthority(
                        tenantId,
                        "Tasco",
                        TenantMembershipRole.OWNER
                ),
                resolver.findSessionAuthority(actorId).orElseThrow()
        );
    }

    @Test
    void resolvesActiveMemberAuthority() {
        var actorId = actor();
        var tenantId = tenant();
        persistAuthority(actorId, tenantId, TenantMembershipRole.MEMBER);

        assertEquals(TenantMembershipRole.MEMBER, resolver.findSessionAuthority(actorId).orElseThrow().role());
    }

    @Test
    void excludesInactiveMembershipAndTenant() {
        var actorId = actor();
        var tenantId = tenant();
        persistAuthority(actorId, tenantId, TenantMembershipRole.OWNER);

        updateStatus("tenant_memberships", "INACTIVE", "tenant_id", tenantId.value());
        assertTrue(resolver.findSessionAuthority(actorId).isEmpty());
        updateStatus("tenant_memberships", "ACTIVE", "tenant_id", tenantId.value());

        updateStatus("tenants", "INACTIVE", "id", tenantId.value());
        assertTrue(resolver.findSessionAuthority(actorId).isEmpty());
        updateStatus("tenants", "ACTIVE", "id", tenantId.value());
    }

    @Test
    void reportsOnlyActiveTenantIdentifiers() {
        var actorId = actor();
        var tenantId = tenant();
        persistAuthority(actorId, tenantId, TenantMembershipRole.OWNER);

        assertTrue(resolver.isActiveTenant(tenantId));
        assertFalse(resolver.isActiveTenant(tenant()));

        updateStatus("tenants", "INACTIVE", "id", tenantId.value());
        assertFalse(resolver.isActiveTenant(tenantId));
    }

    private void persistAuthority(
            ActorId actorId,
            TenantId tenantId,
            TenantMembershipRole role
    ) {
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId) ON CONFLICT DO NOTHING")
                .param("actorId", actorId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:tenantId, :slug, 'Tasco', 'ACTIVE', :bootstrapReference)
                        """)
                .param("tenantId", tenantId.value())
                .param("slug", "tenant-" + tenantId.value())
                .param("bootstrapReference", "test-" + tenantId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, 'ACTIVE')
                        """)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("role", role.name())
                .update();
    }

    private void updateStatus(String table, String value, String idColumn, UUID id) {
        jdbcClient.sql("UPDATE " + table + " SET status = :value WHERE " + idColumn + " = :id")
                .param("value", value)
                .param("id", id)
                .update();
    }

    private static ActorId actor() {
        return new ActorId(UUID.randomUUID());
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

}
