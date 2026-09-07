package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class IamSchemaMigrationTest {

    @Test
    void v14ClassifiesActorsAddsAuthorizationRevisionAndInvalidatesSerializedSessions() throws SQLException {
        DataSource dataSource = databaseBeforeV14();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        UUID actorId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        seedActorTenantMembershipAndSession(jdbcClient, actorId, tenantId, "OWNER");

        applyV14(dataSource);

        assertEquals("STANDARD", jdbcClient.sql("SELECT account_type FROM actors WHERE id = :actorId")
                .param("actorId", actorId)
                .query(String.class)
                .single());
        assertEquals(0L, jdbcClient.sql("SELECT authorization_version FROM tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single());
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM spring_session").query(Long.class).single());
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM spring_session_attributes").query(Long.class).single());
        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        UPDATE actors SET account_type = 'SERVICE' WHERE id = :actorId
                        """)
                .param("actorId", actorId)
                .update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        UPDATE tenant_memberships SET role = 'ADMIN'
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .update());
    }

    @Test
    void v14FailsActionablyRatherThanReclassifyingHistoricalAdminMemberships() throws SQLException {
        DataSource dataSource = databaseBeforeV14();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        seedActorTenantMembershipAndSession(
                jdbcClient,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ADMIN"
        );

        ScriptStatementFailedException failure = assertThrows(
                ScriptStatementFailedException.class,
                () -> applyV14(dataSource)
        );
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        assertTrue(root.getMessage().contains("ck_v14_reconcile_admin_as_owner_or_member_before_iam"));
        assertEquals(0L, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'actors'
                          AND column_name = 'account_type'
                        """).query(Long.class).single());
        assertEquals("ADMIN", jdbcClient.sql("SELECT role FROM tenant_memberships")
                .query(String.class)
                .single());
    }

    private static DataSource databaseBeforeV14() throws SQLException {
        DataSource dataSource = TestDatabase.freshPostgres();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
            new ResourceDatabasePopulator(
                    migration("V1__create_identity_tables.sql"),
                    migration("V2__create_initial_organization_and_sessions.sql"),
                    migration("V3__create_organization_invitations.sql"),
                    migration("V4__collapse_workspace_into_organization.sql"),
                    migration("V5__create_file_source_and_document_schema.sql"),
                    migration("V6__cut_over_organization_to_tenant.sql"),
                    migration("V13__create_actor_profiles.sql")
            ).populate(connection);
        }
        return dataSource;
    }

    private static void applyV14(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            new ResourceDatabasePopulator(migration("V14__consolidate_iam_account_types.sql"))
                    .populate(connection);
        }
    }

    private static ClassPathResource migration(String fileName) {
        return new ClassPathResource("db/migration/" + fileName);
    }

    private static void seedActorTenantMembershipAndSession(
            JdbcClient jdbcClient,
            UUID actorId,
            UUID tenantId,
            String role
    ) {
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:tenantId, :slug, 'Migration', 'ACTIVE', 'MEM-36')
                        """)
                .param("tenantId", tenantId)
                .param("slug", "migration-" + tenantId.toString().substring(0, 8))
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, 'ACTIVE')
                        """)
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .param("role", role)
                .update();
        jdbcClient.sql("""
                        INSERT INTO spring_session (
                            primary_id, session_id, creation_time, last_access_time,
                            max_inactive_interval, expiry_time, principal_name
                        ) VALUES ('primary', 'session', 1, 1, 1800, 1800001, :principal)
                        """)
                .param("principal", actorId.toString())
                .update();
        jdbcClient.sql("""
                        INSERT INTO spring_session_attributes (
                            session_primary_id, attribute_name, attribute_bytes
                        ) VALUES ('primary', 'SPRING_SECURITY_CONTEXT', :bytes)
                        """)
                .param("bytes", new byte[]{1, 2, 3})
                .update();
    }
}
