package io.memoryos.tenant.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class TenantSchemaMigrationTest {

    @Test
    void v6PreservesRowsAndRenamesEveryActiveOwnershipColumn() throws SQLException {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (var connection = dataSource.getConnection()) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                    new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                    new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                    new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql"),
                    new ClassPathResource("db/migration/V5__create_file_source_and_document_schema.sql")
            ).populate(connection);

            var jdbcClient = JdbcClient.create(dataSource);
            UUID actorId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            UUID invitationId = UUID.randomUUID();
            UUID connectorId = UUID.randomUUID();
            jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)")
                    .param("id", actorId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO organizations (id, slug, display_name, status, bootstrap_reference)
                            VALUES (:id, 'migration', 'Migration', 'ACTIVE', 'MEM-24-MIGRATION')
                            """)
                    .param("id", tenantId)
                    .update();
            jdbcClient.sql("""
                            UPDATE organization_bootstrap_state
                            SET initial_organization_id = :tenantId
                            WHERE id = 1
                            """)
                    .param("tenantId", tenantId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO organization_memberships (organization_id, actor_id, role, status)
                            VALUES (:tenantId, :actorId, 'OWNER', 'ACTIVE')
                            """)
                    .param("tenantId", tenantId)
                    .param("actorId", actorId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO organization_invitations (
                                id, organization_id, normalized_email, open_email_key,
                                secret_digest, status, created_by_actor_id, expires_at
                            ) VALUES (
                                :id, :tenantId, 'member@example.com', 'member@example.com',
                                :digest, 'PENDING', :actorId, :expiresAt
                            )
                            """)
                    .param("id", invitationId)
                    .param("tenantId", tenantId)
                    .param("digest", "0".repeat(64))
                    .param("actorId", actorId)
                    .param("expiresAt", OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                    .update();
            jdbcClient.sql("""
                            INSERT INTO connectors (
                                id, organization_id, name, connector_type, status
                            ) VALUES (
                                :id, :tenantId, 'Migration', 'FILE', 'ACTIVE'
                            )
                            """)
                    .param("id", connectorId)
                    .param("tenantId", tenantId)
                    .update();

            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V6__cut_over_organization_to_tenant.sql")
            ).populate(connection);

            assertEquals(tenantId, scalarUuid(jdbcClient, "SELECT id FROM tenants"));
            assertEquals(tenantId, scalarUuid(jdbcClient, "SELECT tenant_id FROM tenant_bootstrap_state"));
            assertEquals(tenantId, scalarUuid(jdbcClient, "SELECT tenant_id FROM tenant_memberships"));
            assertEquals(tenantId, scalarUuid(jdbcClient, "SELECT tenant_id FROM tenant_invitations"));
            assertEquals(tenantId, scalarUuid(jdbcClient, "SELECT tenant_id FROM connectors"));
            assertEquals(1, jdbcClient.sql("SELECT deployment_slot FROM tenants").query(Integer.class).single());
            assertEquals(0L, jdbcClient.sql("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND column_name = 'organization_id'
                    """).query(Long.class).single());
            assertEquals(0L, jdbcClient.sql("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name LIKE 'organization%'
                    """).query(Long.class).single());
        }
    }

    private static UUID scalarUuid(JdbcClient jdbcClient, String sql) {
        return jdbcClient.sql(sql).query(UUID.class).single();
    }
}
