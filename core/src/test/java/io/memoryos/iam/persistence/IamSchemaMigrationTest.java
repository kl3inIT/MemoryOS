package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;

import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class IamSchemaMigrationTest {
    private HikariDataSource dataSource;

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void v14ClassifiesActorsAddsAuthorizationRevisionAndInvalidatesSerializedSessions() throws SQLException {
        dataSource = TestDatabase.freshPostgres("13");
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
        dataSource = TestDatabase.freshPostgres("13");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        seedActorTenantMembershipAndSession(
                jdbcClient,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ADMIN"
        );

        FlywayException failure = assertThrows(
                FlywayException.class,
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


    private static void applyV14(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("14").load().migrate();
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
