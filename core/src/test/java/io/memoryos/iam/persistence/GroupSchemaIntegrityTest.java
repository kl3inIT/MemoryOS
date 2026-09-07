package io.memoryos.iam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.TestDatabase;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class GroupSchemaIntegrityTest {
    private static final UUID TENANT_ONE = uuid("10000000-0000-0000-0000-000000000057");
    private static final UUID TENANT_TWO = uuid("20000000-0000-0000-0000-000000000057");
    private static final UUID ACTOR = uuid("30000000-0000-0000-0000-000000000057");
    private static final UUID GROUP = uuid("40000000-0000-0000-0000-000000000057");

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = JdbcClient.create(TestDatabase.freshPostgres());
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT ck_tenants_deployment_slot").update();
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        persistTenant(TENANT_ONE, "one", 1);
        persistTenant(TENANT_TWO, "two", 2);
        jdbc.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", ACTOR)
                .update();
        jdbc.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
                        """)
                .param("tenantId", TENANT_ONE)
                .param("actorId", ACTOR)
                .update();
        jdbc.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name)
                        VALUES (:tenantId, :groupId, 'Tenant two group')
                        """)
                .param("tenantId", TENANT_TWO)
                .param("groupId", GROUP)
                .update();
    }

    @Test
    void membershipAndCapabilityGrantsRequireTenantQualifiedGroups() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
                        VALUES (:tenantId, :groupId, :actorId)
                        """)
                .param("tenantId", TENANT_TWO)
                .param("groupId", GROUP)
                .param("actorId", ACTOR)
                .update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'GROUPS_READ')
                        """)
                .param("tenantId", TENANT_ONE)
                .param("groupId", GROUP)
                .update());
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM iam_group_memberships")
                .query(Long.class)
                .single());
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM iam_group_capability_grants")
                .query(Long.class)
                .single());
    }

    @Test
    void deletingAnOrdinaryGroupRemovesOnlyItsLinksAndGrants() {
        UUID ordinaryGroup = uuid("50000000-0000-0000-0000-000000000057");
        UUID connector = uuid("60000000-0000-0000-0000-000000000057");
        UUID credential = uuid("70000000-0000-0000-0000-000000000057");
        UUID source = uuid("80000000-0000-0000-0000-000000000057");
        UUID document = uuid("90000000-0000-0000-0000-000000000057");
        jdbc.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name)
                        VALUES (:tenantId, :groupId, 'Disposable group')
                        """)
                .param("tenantId", TENANT_ONE)
                .param("groupId", ordinaryGroup)
                .update();
        jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
                        VALUES (:tenantId, :groupId, :actorId)
                        """)
                .param("tenantId", TENANT_ONE)
                .param("groupId", ordinaryGroup)
                .param("actorId", ACTOR)
                .update();
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'GROUPS_READ')
                        """)
                .param("tenantId", TENANT_ONE)
                .param("groupId", ordinaryGroup)
                .update();
        jdbc.sql("""
                        INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                        VALUES (:id, :tenantId, 'Source', 'FILE', 'ACTIVE')
                        """)
                .param("id", connector)
                .param("tenantId", TENANT_ONE)
                .update();
        jdbc.sql("""
                        INSERT INTO credentials (id, tenant_id, credential_kind, status)
                        VALUES (:id, :tenantId, 'NO_AUTH', 'ACTIVE')
                        """)
                .param("id", credential)
                .param("tenantId", TENANT_ONE)
                .update();
        jdbc.sql("""
                        INSERT INTO connector_credential_pairs (
                            id, tenant_id, connector_id, credential_id, access_type, status
                        ) VALUES (:id, :tenantId, :connectorId, :credentialId, 'PUBLIC', 'NOT_STARTED')
                        """)
                .param("id", source)
                .param("tenantId", TENANT_ONE)
                .param("connectorId", connector)
                .param("credentialId", credential)
                .update();
        jdbc.sql("""
                        INSERT INTO source_group_grants (
                            tenant_id, connector_credential_pair_id, group_id
                        ) VALUES (:tenantId, :sourceId, :groupId)
                        """)
                .param("tenantId", TENANT_ONE)
                .param("sourceId", source)
                .param("groupId", ordinaryGroup)
                .update();
        jdbc.sql("""
                        INSERT INTO documents (id, tenant_id, status)
                        VALUES (:id, :tenantId, 'ELIGIBLE')
                        """)
                .param("id", document)
                .param("tenantId", TENANT_ONE)
                .update();

        jdbc.sql("DELETE FROM iam_groups WHERE tenant_id = :tenantId AND id = :groupId")
                .param("tenantId", TENANT_ONE)
                .param("groupId", ordinaryGroup)
                .update();

        assertEquals(0L, countForGroup("iam_group_memberships", ordinaryGroup));
        assertEquals(0L, countForGroup("iam_group_capability_grants", ordinaryGroup));
        assertEquals(0L, countForGroup("source_group_grants", ordinaryGroup));
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM actors WHERE id = :id")
                .param("id", ACTOR)
                .query(Long.class)
                .single());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM connector_credential_pairs WHERE id = :id")
                .param("id", source)
                .query(Long.class)
                .single());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM documents WHERE id = :id")
                .param("id", document)
                .query(Long.class)
                .single());
    }

    private long countForGroup(String table, UUID groupId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = :tenantId AND group_id = :groupId")
                .param("tenantId", TENANT_ONE)
                .param("groupId", groupId)
                .query(Long.class)
                .single();
    }

    private void persistTenant(UUID tenantId, String suffix, int deploymentSlot) {
        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot
                        ) VALUES (:id, :slug, :name, 'ACTIVE', :reference, :slot)
                        """)
                .param("id", tenantId)
                .param("slug", "tenant-" + suffix)
                .param("name", "Tenant " + suffix)
                .param("reference", "test-" + suffix)
                .param("slot", deploymentSlot)
                .update();
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
