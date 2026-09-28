package io.memoryos.iam.group.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.group.DefaultIamAuthorization;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import io.memoryos.iam.Authority;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class GroupMigrationSeedTest {
    private static final UUID TENANT = uuid("10000000-0000-0000-0000-000000000058");
    private static final UUID OWNER = uuid("20000000-0000-0000-0000-000000000058");
    private static final UUID INACTIVE_MEMBER = uuid("30000000-0000-0000-0000-000000000058");
    private static final UUID ORDINARY_GROUP = uuid("40000000-0000-0000-0000-000000000058");
    private HikariDataSource dataSource;

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "43"})
    void upgradesExistingGroupsWithoutChangingIdentitiesMembershipsOrOrdinaryGrants(String baseline) throws Exception {
        dataSource = TestDatabase.freshPostgres("14");
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

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target(baseline).load().migrate();
        assertEquals(baseline.equals("42") ? 0L : 1L, count(jdbc, """
                SELECT COUNT(*) FROM iam_group_capability_grants
                WHERE tenant_id = :tenantId AND capability = 'BASIC_ACCESS'
                """));
        jdbc.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name)
                        VALUES (:tenantId, :groupId, 'Existing ordinary group')
                        """)
                .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP).update();
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'USERS_MANAGE'),
                               (:tenantId, :groupId, 'MODELS_MANAGE')
                        """)
                .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP).update();
        jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id, is_manager)
                        VALUES (:tenantId, :groupId, :actorId, TRUE)
                        """)
                .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP)
                .param("actorId", OWNER).update();
        jdbc.sql("UPDATE tenants SET authorization_version = 7 WHERE id = :tenantId")
                .param("tenantId", TENANT).update();
        var membershipsBefore = jdbc.sql("""
                        SELECT group_id || ':' || actor_id || ':' || is_manager AS membership
                        FROM iam_group_memberships WHERE tenant_id = :tenantId
                        ORDER BY group_id, actor_id
                        """)
                .param("tenantId", TENANT).query(String.class).list();

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("43").load().migrate();

        long versionBeforeRename = baseline.equals("42") ? 8L : 7L;
        assertEquals(versionBeforeRename, jdbc.sql("SELECT authorization_version FROM tenants WHERE id = :tenantId")
                .param("tenantId", TENANT).query(Long.class).single());
        assertEquals(membershipsBefore, jdbc.sql("""
                        SELECT group_id || ':' || actor_id || ':' || is_manager AS membership
                        FROM iam_group_memberships WHERE tenant_id = :tenantId
                        ORDER BY group_id, actor_id
                        """)
                .param("tenantId", TENANT).query(String.class).list());
        assertEquals("BASIC_ACCESS", jdbc.sql("""
                        SELECT grant_record.capability
                        FROM iam_group_capability_grants grant_record
                        JOIN iam_groups group_record
                          ON group_record.tenant_id = grant_record.tenant_id
                         AND group_record.id = grant_record.group_id
                        WHERE group_record.tenant_id = :tenantId AND group_record.system_key = 'BASIC'
                        """)
                .param("tenantId", TENANT).query(String.class).single());
        assertEquals(List.of("MODELS_MANAGE", "USERS_MANAGE"), jdbc.sql("""
                        SELECT capability FROM iam_group_capability_grants
                        WHERE tenant_id = :tenantId AND group_id = :groupId
                        ORDER BY capability
                        """)
                .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP)
                .query(String.class).list());

        assertEquals(3L, count(jdbc, "SELECT COUNT(*) FROM iam_groups WHERE tenant_id = :tenantId"));
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

        var groupsBeforeRename = jdbc.sql("""
                        SELECT row_to_json(group_record)::text
                        FROM iam_groups group_record WHERE tenant_id = :tenantId ORDER BY id
                        """)
                .param("tenantId", TENANT).query(String.class).list();
        var tenantMembershipsBeforeRename = jdbc.sql("""
                        SELECT row_to_json(membership)::text
                        FROM tenant_memberships membership WHERE tenant_id = :tenantId ORDER BY actor_id
                        """)
                .param("tenantId", TENANT).query(String.class).list();
        var grantsBeforeRename = jdbc.sql("""
                        SELECT group_id || ':' || capability || ':' || created_at
                        FROM iam_group_capability_grants WHERE tenant_id = :tenantId ORDER BY group_id, capability
                        """)
                .param("tenantId", TENANT).query(String.class).list();
        var expectedGrants = grantsBeforeRename.stream()
                .map(grant -> grant.replace(":IAM_ADMIN:", ":SYSTEM_ADMIN:")
                        .replace(":BASIC_ACCESS:", ":SYSTEM_BASIC:"))
                .toList();

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("46").load().migrate();

        assertEquals(versionBeforeRename + 1,
                jdbc.sql("SELECT authorization_version FROM tenants WHERE id = :tenantId")
                        .param("tenantId", TENANT).query(Long.class).single());
        assertEquals(groupsBeforeRename, jdbc.sql("""
                        SELECT row_to_json(group_record)::text
                        FROM iam_groups group_record WHERE tenant_id = :tenantId ORDER BY id
                        """)
                .param("tenantId", TENANT).query(String.class).list());
        assertEquals(membershipsBefore, jdbc.sql("""
                        SELECT group_id || ':' || actor_id || ':' || is_manager AS membership
                        FROM iam_group_memberships WHERE tenant_id = :tenantId ORDER BY group_id, actor_id
                        """)
                .param("tenantId", TENANT).query(String.class).list());
        assertEquals(tenantMembershipsBeforeRename, jdbc.sql("""
                        SELECT row_to_json(membership)::text
                        FROM tenant_memberships membership WHERE tenant_id = :tenantId ORDER BY actor_id
                        """)
                .param("tenantId", TENANT).query(String.class).list());
        assertEquals(List.of("SYSTEM_ADMIN", "SYSTEM_BASIC"), jdbc.sql("""
                        SELECT grant_record.capability
                        FROM iam_group_capability_grants grant_record
                        JOIN iam_groups group_record
                          ON group_record.tenant_id = grant_record.tenant_id
                         AND group_record.id = grant_record.group_id
                        WHERE group_record.tenant_id = :tenantId AND group_record.system_key IS NOT NULL
                        ORDER BY group_record.system_key
                        """)
                .param("tenantId", TENANT).query(String.class).list());
        for (UUID groupId : Set.of(GroupEntity.ADMIN_ID, GroupEntity.BASIC_ID, ORDINARY_GROUP)) {
            for (String retired : List.of("IAM_ADMIN", "BASIC_ACCESS")) {
                assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                                INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                                VALUES (:tenantId, :groupId, :capability)
                                """)
                        .param("tenantId", TENANT).param("groupId", groupId)
                        .param("capability", retired).update());
            }
            for (IamCapability capability : IamCapability.values()) {
                boolean allowed = groupId.equals(GroupEntity.ADMIN_ID)
                        ? capability == IamCapability.SYSTEM_ADMIN
                        : groupId.equals(GroupEntity.BASIC_ID)
                                ? capability == IamCapability.SYSTEM_BASIC
                                : capability.isOrdinaryGrant();
                if (!allowed) {
                    assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                                    UPDATE iam_group_capability_grants SET capability = :capability
                                    WHERE tenant_id = :tenantId AND group_id = :groupId
                                    """)
                            .param("tenantId", TENANT).param("groupId", groupId)
                            .param("capability", capability.name()).update());
                }
            }
        }
        for (UUID systemGroupId : Set.of(GroupEntity.ADMIN_ID, GroupEntity.BASIC_ID)) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                            UPDATE iam_group_capability_grants SET group_id = :ordinaryGroupId
                            WHERE tenant_id = :tenantId AND group_id = :systemGroupId
                            """)
                    .param("tenantId", TENANT).param("ordinaryGroupId", ORDINARY_GROUP)
                    .param("systemGroupId", systemGroupId).update());
        }
        assertEquals(expectedGrants, jdbc.sql("""
                        SELECT group_id || ':' || capability || ':' || created_at
                        FROM iam_group_capability_grants WHERE tenant_id = :tenantId ORDER BY group_id, capability
                        """)
                .param("tenantId", TENANT).query(String.class).list());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revokesDirectGroupReadWithoutElevatingOrChangingOtherState(boolean hasDirectRead) throws Exception {
        dataSource = TestDatabase.freshPostgres("44");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID readOnlyGroup = uuid("50000000-0000-0000-0000-000000000058");
        UUID reader = uuid("60000000-0000-0000-0000-000000000058");
        UUID manager = uuid("70000000-0000-0000-0000-000000000058");
        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot,
                            authorization_version
                        ) VALUES (:id, 'group-read-upgrade', 'Group read upgrade', 'ACTIVE', 'test', 1, 7)
                        """)
                .param("id", TENANT).update();
        persistMember(jdbc, reader, "MEMBER", "ACTIVE");
        persistMember(jdbc, manager, "MEMBER", "ACTIVE");
        for (UUID actor : List.of(reader, manager)) {
            jdbc.sql("""
                            INSERT INTO external_identity_bindings (issuer, subject, actor_id)
                            VALUES ('group-read-upgrade', :subject, :actorId)
                            """)
                    .param("subject", actor.toString()).param("actorId", actor).update();
        }
        jdbc.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name)
                        VALUES (:tenantId, :readerGroup, 'Read only'), (:tenantId, :managerGroup, 'Managers')
                        """)
                .param("tenantId", TENANT).param("readerGroup", readOnlyGroup)
                .param("managerGroup", ORDINARY_GROUP).update();
        jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id, is_manager)
                        VALUES (:tenantId, :readerGroup, :reader, FALSE),
                               (:tenantId, :managerGroup, :manager, TRUE)
                        """)
                .param("tenantId", TENANT).param("readerGroup", readOnlyGroup)
                .param("managerGroup", ORDINARY_GROUP).param("reader", reader).param("manager", manager).update();
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'GROUPS_MANAGE'),
                               (:tenantId, :groupId, 'USERS_MANAGE'),
                               (:tenantId, :groupId, 'MODELS_MANAGE')
                        """)
                .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP).update();
        if (hasDirectRead) {
            jdbc.sql("""
                            INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                            VALUES (:tenantId, :readerGroup, 'GROUPS_READ'),
                                   (:tenantId, :managerGroup, 'GROUPS_READ')
                            """)
                    .param("tenantId", TENANT).param("readerGroup", readOnlyGroup)
                    .param("managerGroup", ORDINARY_GROUP).update();
        }
        persistSourceAssociations(jdbc, readOnlyGroup, ORDINARY_GROUP);

        Map<String, List<String>> unchangedRows = List.of(
                        "actors", "external_identity_bindings", "tenant_memberships", "iam_groups",
                        "iam_group_memberships", "connectors", "credentials", "connector_credential_pairs",
                        "source_group_grants")
                .stream().collect(Collectors.toMap(table -> table, table -> tableRows(jdbc, table)));
        var tenantBefore = jdbc.sql("""
                        SELECT (to_jsonb(tenant) - 'authorization_version')::text FROM tenants tenant
                        WHERE id = :tenantId
                        """)
                .param("tenantId", TENANT).query(String.class).single();
        var expectedGrants = jdbc.sql("""
                        SELECT row_to_json(grant_record)::text
                        FROM iam_group_capability_grants grant_record
                        WHERE capability <> 'GROUPS_READ' ORDER BY 1
                        """)
                .query(String.class).list();

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("46").load().migrate();

        assertEquals(hasDirectRead ? 8L : 7L,
                jdbc.sql("SELECT authorization_version FROM tenants WHERE id = :tenantId")
                        .param("tenantId", TENANT).query(Long.class).single());
        assertEquals(tenantBefore, jdbc.sql("""
                        SELECT (to_jsonb(tenant) - 'authorization_version')::text FROM tenants tenant
                        WHERE id = :tenantId
                        """)
                .param("tenantId", TENANT).query(String.class).single());
        unchangedRows.forEach((table, rows) -> assertEquals(rows, tableRows(jdbc, table), table));
        assertEquals(expectedGrants, tableRows(jdbc, "iam_group_capability_grants"));
        assertEquals(0L, count(jdbc, """
                SELECT COUNT(*) FROM iam_group_capability_grants
                WHERE tenant_id = :tenantId AND capability = 'GROUPS_READ'
                """));
        assertEquals(List.of(), jdbc.sql("""
                        SELECT capability FROM iam_group_capability_grants
                        WHERE tenant_id = :tenantId AND group_id = :groupId
                        """)
                .param("tenantId", TENANT).param("groupId", readOnlyGroup).query(String.class).list());

        var authorization = new DefaultIamAuthorization(
                new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        assertEquals(Set.of(), authorization.effectiveCapabilities(new ActorId(reader)));
        assertEquals(Set.of(), authorization.scopedCapabilities(new ActorId(reader)));
        assertEquals(Set.of(IamCapability.GROUPS_MANAGE, IamCapability.GROUPS_READ,
                        IamCapability.USERS_MANAGE, IamCapability.MODELS_MANAGE),
                authorization.effectiveCapabilities(new ActorId(manager)));
        for (UUID group : List.of(readOnlyGroup, ORDINARY_GROUP)) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                            INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                            VALUES (:tenantId, :groupId, 'GROUPS_READ')
                            """)
                    .param("tenantId", TENANT).param("groupId", group).update());
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        UPDATE iam_group_capability_grants SET capability = 'GROUPS_READ'
                        WHERE tenant_id = :tenantId AND group_id = :groupId AND capability = 'GROUPS_MANAGE'
                        """)
                .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP).update());
        assertEquals(expectedGrants, tableRows(jdbc, "iam_group_capability_grants"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked-only", "manager-only", "combined", "unaffected"})
    void unifiesSourceManagementWithoutElevatingStandaloneGrantsOrChangingOtherState(String scenario) throws Exception {
        dataSource = TestDatabase.freshPostgres("45");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        boolean hasRevokedGrants = scenario.equals("revoked-only") || scenario.equals("combined");
        boolean hasManagementGrant = scenario.equals("manager-only") || scenario.equals("combined");
        UUID readOnlyGroup = uuid("50000000-0000-0000-0000-000000000058");
        UUID deleteOnlyGroup = uuid("b0000000-0000-0000-0000-000000000058");
        UUID reader = uuid("60000000-0000-0000-0000-000000000058");
        UUID deleter = uuid("c0000000-0000-0000-0000-000000000058");
        UUID manager = uuid("70000000-0000-0000-0000-000000000058");
        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot,
                            authorization_version
                        ) VALUES (:id, 'source-management-upgrade', 'Source management upgrade', 'ACTIVE', 'test', 1, 7)
                        """)
                .param("id", TENANT).update();
        Map<UUID, UUID> membersByGroup = Map.of(
                readOnlyGroup, reader, deleteOnlyGroup, deleter, ORDINARY_GROUP, manager);
        membersByGroup.forEach((group, actor) -> {
            persistMember(jdbc, actor, "MEMBER", "ACTIVE");
            jdbc.sql("""
                            INSERT INTO external_identity_bindings (issuer, subject, actor_id)
                            VALUES ('source-management-upgrade', :subject, :actorId)
                            """)
                    .param("subject", actor.toString()).param("actorId", actor).update();
            jdbc.sql("""
                            INSERT INTO iam_groups (tenant_id, id, name)
                            VALUES (:tenantId, :groupId, :name)
                            """)
                    .param("tenantId", TENANT).param("groupId", group).param("name", group.toString()).update();
            jdbc.sql("""
                            INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id, is_manager)
                            VALUES (:tenantId, :groupId, :actorId, :isManager)
                            """)
                    .param("tenantId", TENANT).param("groupId", group).param("actorId", actor)
                    .param("isManager", group.equals(ORDINARY_GROUP)).update();
        });
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'GROUPS_MANAGE'),
                               (:tenantId, :groupId, 'USERS_MANAGE'),
                               (:tenantId, :groupId, 'MODELS_MANAGE')
                        """)
                .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP).update();
        if (hasRevokedGrants) {
            jdbc.sql("""
                            INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                            VALUES (:tenantId, :readerGroup, 'SOURCES_READ'),
                                   (:tenantId, :deleterGroup, 'SOURCES_DELETE'),
                                   (:tenantId, :managerGroup, 'SOURCES_READ'),
                                   (:tenantId, :managerGroup, 'SOURCES_DELETE')
                            """)
                    .param("tenantId", TENANT).param("readerGroup", readOnlyGroup)
                    .param("deleterGroup", deleteOnlyGroup).param("managerGroup", ORDINARY_GROUP).update();
        }
        if (hasManagementGrant) {
            jdbc.sql("""
                            INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                            VALUES (:tenantId, :groupId, 'SOURCES_MANAGE')
                            """)
                    .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP).update();
        }
        persistSourceAssociations(jdbc, readOnlyGroup, deleteOnlyGroup, ORDINARY_GROUP);
        Map<String, List<String>> unchangedRows = List.of(
                        "actors", "external_identity_bindings", "tenant_memberships", "iam_groups",
                        "iam_group_memberships", "connectors", "credentials", "connector_credential_pairs",
                        "source_group_grants")
                .stream().collect(Collectors.toMap(table -> table, table -> tableRows(jdbc, table)));
        var tenantBefore = jdbc.sql("""
                        SELECT (to_jsonb(tenant) - 'authorization_version')::text FROM tenants tenant
                        WHERE id = :tenantId
                        """)
                .param("tenantId", TENANT).query(String.class).single();
        var expectedGrants = jdbc.sql("""
                        SELECT row_to_json(grant_record)::text
                        FROM iam_group_capability_grants grant_record
                        WHERE capability NOT IN ('SOURCES_READ', 'SOURCES_DELETE') ORDER BY 1
                        """)
                .query(String.class).list();

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("46").load().migrate();

        assertEquals(hasRevokedGrants || hasManagementGrant ? 8L : 7L,
                jdbc.sql("SELECT authorization_version FROM tenants WHERE id = :tenantId")
                        .param("tenantId", TENANT).query(Long.class).single());
        assertEquals(tenantBefore, jdbc.sql("""
                        SELECT (to_jsonb(tenant) - 'authorization_version')::text FROM tenants tenant
                        WHERE id = :tenantId
                        """)
                .param("tenantId", TENANT).query(String.class).single());
        unchangedRows.forEach((table, rows) -> assertEquals(rows, tableRows(jdbc, table), table));
        assertEquals(expectedGrants, tableRows(jdbc, "iam_group_capability_grants"));

        var authorization = new DefaultIamAuthorization(
                new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        for (UUID actor : List.of(reader, deleter)) {
            assertEquals(Set.of(), authorization.effectiveCapabilities(new ActorId(actor)));
            assertEquals(Set.of(), authorization.scopedCapabilities(new ActorId(actor)));
        }
        assertEquals(hasManagementGrant
                        ? Set.of(IamCapability.USERS_MANAGE, IamCapability.GROUPS_MANAGE, IamCapability.GROUPS_READ,
                                IamCapability.SOURCES_MANAGE, IamCapability.SOURCES_READ, IamCapability.SOURCES_DELETE,
                                IamCapability.MODELS_MANAGE)
                        : Set.of(IamCapability.USERS_MANAGE, IamCapability.GROUPS_MANAGE, IamCapability.GROUPS_READ,
                                IamCapability.MODELS_MANAGE),
                authorization.effectiveCapabilities(new ActorId(manager)));
        for (String capability : List.of("SOURCES_READ", "SOURCES_DELETE")) {
            for (UUID group : membersByGroup.keySet()) {
                assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                                INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                                VALUES (:tenantId, :groupId, :capability)
                                """)
                        .param("tenantId", TENANT).param("groupId", group)
                        .param("capability", capability).update());
            }
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                            UPDATE iam_group_capability_grants SET capability = :capability
                            WHERE tenant_id = :tenantId AND group_id = :groupId AND capability = 'USERS_MANAGE'
                            """)
                    .param("tenantId", TENANT).param("groupId", ORDINARY_GROUP)
                    .param("capability", capability).update());
        }
        assertEquals(expectedGrants, tableRows(jdbc, "iam_group_capability_grants"));
    }

    @Test
    void managerScopeUpgradePreservesLegacyReachAndDoesNotInventOwnership() throws Exception {
        dataSource = TestDatabase.freshPostgres("46");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference,
                            deployment_slot, authorization_version)
                        VALUES (:tenant, 'manager-upgrade', 'Manager upgrade', 'ACTIVE', 'test', 1, 7)
                        """).param("tenant", TENANT).update();
        persistMember(jdbc, OWNER, "OWNER", "ACTIVE");
        jdbc.sql("INSERT INTO iam_groups (tenant_id,id,name) VALUES (:tenant,:group,'Existing group')")
                .param("tenant", TENANT).param("group", ORDINARY_GROUP).update();
        jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id,group_id,actor_id,is_manager)
                        VALUES (:tenant,:group,:actor,TRUE)
                        """).param("tenant", TENANT).param("group", ORDINARY_GROUP).param("actor", OWNER).update();
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id,group_id,capability)
                        VALUES (:tenant,:group,'MODELS_MANAGE')
                        """).param("tenant", TENANT).param("group", ORDINARY_GROUP).update();
        persistSourceAssociations(jdbc, ORDINARY_GROUP);
        UUID drive = uuid("d0000000-0000-0000-0000-000000000058");
        UUID selection = uuid("e0000000-0000-0000-0000-000000000058");
        UUID cleanup = uuid("f0000000-0000-0000-0000-000000000058");
        jdbc.sql("""
                        INSERT INTO credentials (id,tenant_id,name,credential_kind,status)
                        VALUES (:id,:tenant,'Legacy Drive','GOOGLE_OAUTH','REVOKED')
                        """).param("id", drive).param("tenant", TENANT).update();
        jdbc.sql("""
                        INSERT INTO google_drive_credentials
                            (tenant_id,credential_id,account_subject,account_email,granted_scopes,connection_status)
                        VALUES (:tenant,:id,'legacy-subject','legacy@example.test','drive.readonly','REVOKED')
                        """).param("id", drive).param("tenant", TENANT).update();
        jdbc.sql("""
                        INSERT INTO connectors (id,tenant_id,name,connector_type,status)
                        VALUES (:id,:tenant,'Legacy Drive','GOOGLE_DRIVE','ACTIVE')
                        """).param("id", drive).param("tenant", TENANT).update();
        jdbc.sql("""
                        INSERT INTO connector_credential_pairs
                            (id,tenant_id,connector_id,credential_id,access_type,status)
                        VALUES (:id,:tenant,:id,:id,'RESTRICTED','NOT_STARTED')
                        """).param("id", drive).param("tenant", TENANT).update();
        jdbc.sql("""
                        INSERT INTO google_drive_sources (tenant_id,source_id,next_sync_at,sync_interval_minutes)
                        VALUES (:tenant,:id,'2027-01-01T00:00:00Z',15)
                        """).param("id", drive).param("tenant", TENANT).update();
        jdbc.sql("""
                        INSERT INTO source_group_grants (tenant_id,connector_credential_pair_id,group_id)
                        VALUES (:tenant,:id,:group)
                        """).param("id", drive).param("tenant", TENANT).param("group", ORDINARY_GROUP).update();
        jdbc.sql("""
                        INSERT INTO google_drive_selection_operations
                            (id,tenant_id,source_id,actor_id,request_id,request_hash,credential_id,
                             credential_revision,scope_revision,discovery_revision,scope_mode,source_name,
                             max_requests,max_metadata,max_roots,max_request_bytes,status,completed_at)
                        VALUES (:id,:tenant,:source,:actor,:id,REPEAT('a',64),:source,
                            1,0,0,'GENERAL','Legacy Drive',100,100,100,10000,'SUCCEEDED',CURRENT_TIMESTAMP)
                        """).param("id", selection).param("source", drive).param("tenant", TENANT)
                .param("actor", OWNER).update();
        jdbc.sql("""
                        INSERT INTO connector_cleanup_attempts
                            (id,tenant_id,operation,target_key,target_pair_id,status,completed_at)
                        VALUES (:id,:tenant,'DELETE_SOURCE','old-source',:id,'SUCCEEDED',CURRENT_TIMESTAMP)
                        """).param("id", cleanup).param("tenant", TENANT).update();
        var stableTables = List.of("actors", "tenant_memberships", "iam_groups", "iam_group_memberships",
                "iam_group_capability_grants", "source_group_grants", "google_drive_credentials");
        var before = stableTables.stream().collect(Collectors.toMap(table -> table, table -> tableRows(jdbc, table)));
        var addedColumns = Map.of(
                "connector_credential_pairs", "created_by_actor_id",
                "credentials", "owner_actor_id",
                "google_drive_selection_operations", "group_ids",
                "google_drive_sources", "sync_paused",
                "connector_cleanup_attempts", "scope_owner_actor_id",
                "tenants", "authorization_version");
        var oldShapes = addedColumns.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> jdbc.sql("SELECT (to_jsonb(record) - '" + entry.getValue()
                                + "')::text FROM " + entry.getKey() + " record ORDER BY 1")
                        .query(String.class).list()));
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("47").load().migrate();
        before.forEach((table, rows) -> assertEquals(rows, tableRows(jdbc, table), table));
        addedColumns.forEach((table, column) ->
                assertEquals(oldShapes.get(table), jdbc.sql("SELECT (to_jsonb(record) - '" + column
                                + "')::text FROM " + table + " record ORDER BY 1").query(String.class).list(), table));
        assertEquals(8L, jdbc.sql("SELECT authorization_version FROM tenants WHERE id=:tenant")
                .param("tenant", TENANT).query(Long.class).single());
        assertEquals(0L, count(jdbc, "SELECT COUNT(*) FROM connector_credential_pairs WHERE tenant_id=:tenantId AND created_by_actor_id IS NOT NULL"));
        assertEquals(0L, count(jdbc, "SELECT COUNT(*) FROM credentials WHERE tenant_id=:tenantId AND owner_actor_id IS NOT NULL"));
        assertEquals(false, jdbc.sql("SELECT sync_paused FROM google_drive_sources WHERE tenant_id=:tenant")
                .param("tenant", TENANT).query(Boolean.class).single());
        assertEquals("[]", jdbc.sql("SELECT group_ids::text FROM google_drive_selection_operations WHERE id=:id")
                .param("id", selection).query(String.class).single());
        assertEquals(0L, count(jdbc, "SELECT COUNT(*) FROM connector_cleanup_attempts WHERE tenant_id=:tenantId AND scope_owner_actor_id IS NOT NULL"));
        UUID outsider = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", outsider).update();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        UPDATE connector_credential_pairs SET created_by_actor_id=:actor WHERE id=:source
                        """).param("actor", outsider).param("source", drive).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        UPDATE credentials SET owner_actor_id=:actor WHERE id=:id
                        """).param("actor", outsider).param("id", drive).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        UPDATE credentials SET owner_actor_id=:actor WHERE credential_kind='NO_AUTH'
                        """).param("actor", OWNER).update());
    }

    @Test
    void removesSystemSourceAssociationsWithoutChangingAdminAuthorityOrOrdinaryLinks() throws Exception {
        dataSource = TestDatabase.freshPostgres("14");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference, deployment_slot)
                        VALUES (:tenant, 'ordinary-source-groups', 'Ordinary Source groups', 'ACTIVE', 'test', 1)
                        """).param("tenant", TENANT).update();
        persistMember(jdbc, OWNER, "OWNER", "ACTIVE");
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("47").load().migrate();
        jdbc.sql("INSERT INTO iam_groups (tenant_id,id,name) VALUES (:tenant,:group,'Ordinary group')")
                .param("tenant", TENANT).param("group", ORDINARY_GROUP).update();
        List<UUID> systemGroups = jdbc.sql("""
                        SELECT id FROM iam_groups
                        WHERE tenant_id=:tenant AND system_key IS NOT NULL ORDER BY system_key
                        """).param("tenant", TENANT).query(UUID.class).list();
        persistSourceAssociations(jdbc, systemGroups.get(0), systemGroups.get(1), ORDINARY_GROUP);
        var stableTables = List.of("actors", "tenant_memberships", "iam_groups", "iam_group_memberships",
                "iam_group_capability_grants", "connectors", "credentials", "connector_credential_pairs");
        var before = stableTables.stream().collect(Collectors.toMap(table -> table, table -> tableRows(jdbc, table)));
        var ordinaryLinks = jdbc.sql("""
                        SELECT row_to_json(grant_record)::text FROM source_group_grants grant_record
                        WHERE group_id=:group ORDER BY 1
                        """).param("group", ORDINARY_GROUP).query(String.class).list();
        long version = jdbc.sql("SELECT authorization_version FROM tenants WHERE id=:tenant")
                .param("tenant", TENANT).query(Long.class).single();
        var authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        var adminCapabilities = authorization.effectiveCapabilities(new ActorId(OWNER));

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").target("48").load().migrate();

        before.forEach((table, rows) -> assertEquals(rows, tableRows(jdbc, table), table));
        assertEquals(ordinaryLinks, tableRows(jdbc, "source_group_grants"));
        assertEquals(version + 1, jdbc.sql("SELECT authorization_version FROM tenants WHERE id=:tenant")
                .param("tenant", TENANT).query(Long.class).single());
        assertEquals(adminCapabilities, authorization.effectiveCapabilities(new ActorId(OWNER)));
        assertEquals(Authority.GLOBAL,
                authorization.require(new ActorId(OWNER), IamCapability.SOURCES_MANAGE, false).authority());
        UUID source = uuid("a0000000-0000-0000-0000-000000000058");
        for (UUID systemGroup : systemGroups) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                            INSERT INTO source_group_grants (tenant_id,connector_credential_pair_id,group_id)
                            VALUES (:tenant,:source,:group)
                            """).param("tenant", TENANT).param("source", source).param("group", systemGroup).update());
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                            UPDATE source_group_grants SET group_id=:group
                            WHERE tenant_id=:tenant AND connector_credential_pair_id=:source
                            """).param("group", systemGroup).param("tenant", TENANT).param("source", source).update());
        }
        assertEquals(ordinaryLinks, tableRows(jdbc, "source_group_grants"));
    }

    private static void persistSourceAssociations(JdbcClient jdbc, UUID... groups) {
        UUID connector = uuid("80000000-0000-0000-0000-000000000058");
        UUID credential = uuid("90000000-0000-0000-0000-000000000058");
        UUID source = uuid("a0000000-0000-0000-0000-000000000058");
        jdbc.sql("""
                        INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                        VALUES (:id, :tenantId, 'Source', 'FILE', 'ACTIVE')
                        """)
                .param("id", connector).param("tenantId", TENANT).update();
        jdbc.sql("""
                        INSERT INTO credentials (id, tenant_id, name, credential_kind, status)
                        VALUES (:id, :tenantId, 'No authentication', 'NO_AUTH', 'ACTIVE')
                        """)
                .param("id", credential).param("tenantId", TENANT).update();
        jdbc.sql("""
                        INSERT INTO connector_credential_pairs (
                            id, tenant_id, connector_id, credential_id, access_type, status
                        ) VALUES (:id, :tenantId, :connectorId, :credentialId, 'PUBLIC', 'NOT_STARTED')
                        """)
                .param("id", source).param("tenantId", TENANT)
                .param("connectorId", connector).param("credentialId", credential).update();
        for (UUID group : groups) {
            jdbc.sql("""
                            INSERT INTO source_group_grants (tenant_id, connector_credential_pair_id, group_id)
                            VALUES (:tenantId, :sourceId, :groupId)
                            """)
                    .param("tenantId", TENANT).param("sourceId", source).param("groupId", group).update();
        }
    }

    private static List<String> tableRows(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT row_to_json(record)::text FROM " + table + " record ORDER BY 1")
                .query(String.class).list();
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
