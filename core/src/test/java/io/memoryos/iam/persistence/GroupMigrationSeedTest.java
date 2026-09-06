package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.memoryos.TestDatabase;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class GroupMigrationSeedTest {
    private static final UUID TENANT = uuid("10000000-0000-0000-0000-000000000058");
    private static final UUID OWNER = uuid("20000000-0000-0000-0000-000000000058");
    private static final UUID INACTIVE_MEMBER = uuid("30000000-0000-0000-0000-000000000058");

    @Test
    void seedsAdminOwnerAndEveryExistingMembershipIntoBasicWithoutChangingActivation() throws Exception {
        var dataSource = TestDatabase.freshPostgres("14");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot
                        ) VALUES (:id, 'group-seed-test', 'Group seed test', 'ACTIVE', 'test', 1)
                        """)
                .param("id", TENANT)
                .update();
        persistMember(jdbc, OWNER, "OWNER", "ACTIVE");
        persistMember(jdbc, INACTIVE_MEMBER, "MEMBER", "INACTIVE");

        org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();

        assertEquals(2L, count(jdbc, "SELECT COUNT(*) FROM iam_groups WHERE tenant_id = :tenantId"));
        assertEquals(1L, count(jdbc, """
                SELECT COUNT(*)
                FROM iam_group_capability_grants grant_record
                JOIN iam_groups group_record
                  ON group_record.tenant_id = grant_record.tenant_id
                 AND group_record.id = grant_record.group_id
                WHERE group_record.tenant_id = :tenantId
                  AND group_record.system_key = 'ADMIN'
                  AND grant_record.capability = 'IAM_ADMIN'
                """));
        assertEquals(2L, count(jdbc, """
                SELECT COUNT(*)
                FROM iam_group_memberships membership
                JOIN iam_groups group_record
                  ON group_record.tenant_id = membership.tenant_id
                 AND group_record.id = membership.group_id
                WHERE group_record.tenant_id = :tenantId
                  AND group_record.system_key = 'BASIC'
                """));
        assertEquals(1L, count(jdbc, """
                SELECT COUNT(*)
                FROM iam_group_memberships membership
                JOIN iam_groups group_record
                  ON group_record.tenant_id = membership.tenant_id
                 AND group_record.id = membership.group_id
                WHERE group_record.tenant_id = :tenantId
                  AND group_record.system_key = 'ADMIN'
                  AND membership.actor_id = '20000000-0000-0000-0000-000000000058'
                """));
        assertEquals("INACTIVE", jdbc.sql("""
                        SELECT status
                        FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT)
                .param("actorId", INACTIVE_MEMBER)
                .query(String.class)
                .single());
    }

    private static void persistMember(JdbcClient jdbc, UUID actorId, String role, String status) {
        jdbc.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId)
                .update();
        jdbc.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, :status)
                        """)
                .param("tenantId", TENANT)
                .param("actorId", actorId)
                .param("role", role)
                .param("status", status)
                .update();
    }

    private static long count(JdbcClient jdbc, String statement) {
        return jdbc.sql(statement)
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
