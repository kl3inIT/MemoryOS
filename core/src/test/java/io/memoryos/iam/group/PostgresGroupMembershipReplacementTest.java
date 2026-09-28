package io.memoryos.iam.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupPermissions;
import io.memoryos.iam.GroupService;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamException;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupEntity;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;

import java.sql.Types;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresGroupMembershipReplacementTest {
    private static final TenantId TENANT = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000059")
    );
    private static final ActorId ADMIN = actor("20000000-0000-0000-0000-000000000059");
    private static final ActorId MEMBER = actor("30000000-0000-0000-0000-000000000059");
    private static final GroupId RETAINED = group("40000000-0000-0000-0000-000000000059");
    private static final GroupId ADDED = group("50000000-0000-0000-0000-000000000059");
    private static final GroupId REMOVED = group("60000000-0000-0000-0000-000000000059");

    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private HikariDataSource dataSource;
    private GroupService groups;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);

        jpa = TestDatabase.jpa(dataSource);
        var entityManager = jpa.entityManager();
        var transactionManager = jpa.transactionManager();

        var locks = new IamLockRepository(jdbc);
        IamAuthorization authorization = TestDatabase.transactionalProxy(
                new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks),
                IamAuthorization.class,
                transactionManager
        );
        GroupAdministrationGuard administrationGuard = TestDatabase.transactionalProxy(
                new GroupAdministrationGuard(new GroupInvariantRepository(jdbc)),
                GroupAdministrationGuard.class,
                transactionManager
        );
        GroupService target = new DefaultGroupService(
                authorization,
                new GroupRepository(entityManager),
                new GroupMembershipRepository(entityManager),
                new GroupCapabilityGrantRepository(entityManager),
                new GroupProjectionRepository(jdbc),
                new GroupInvariantRepository(jdbc),
                administrationGuard,
                TestDatabase.audit(jdbc, transactionManager)
        );
        groups = TestDatabase.transactionalProxy(target, GroupService.class, transactionManager);
        seed();
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
    void ordinaryMembershipReplacementPreservesSystemEdgesAndRetainedManagerFlags() {
        groups.replaceOrdinaryMemberships(ADMIN, MEMBER, Set.of(RETAINED, ADDED));

        assertTrue(managerFlag(RETAINED));
        assertFalse(managerFlag(ADDED));
        assertEquals(0L, membershipCount(REMOVED, MEMBER));
        assertEquals(1L, membershipCount(new GroupId(GroupEntity.BASIC_ID), MEMBER));
        assertEquals(1L, authorizationVersion());
        assertEquals(List.of("user.group_change"), actions());
    }

    @Test
    void scopedManagerDelegatesAndRevokesPeerScopeAndCanRemovePeerMembership() {
        ActorId peer = actor("70000000-0000-0000-0000-000000000059");
        persistActor(peer, "MEMBER");
        persistMembership(new GroupId(GroupEntity.BASIC_ID), peer, false);
        groups.addMembers(MEMBER, RETAINED, Set.of(peer));

        assertEquals("IAM_ACCESS_DENIED",
                assertThrows(IamException.class, () -> groups.get(peer, RETAINED)).code());
        groups.assignManager(MEMBER, RETAINED, peer);
        assertEquals(RETAINED, groups.get(peer, RETAINED).id());
        groups.removeManager(MEMBER, RETAINED, peer);
        assertEquals("IAM_ACCESS_DENIED",
                assertThrows(IamException.class, () -> groups.get(peer, RETAINED)).code());
        groups.assignManager(MEMBER, RETAINED, peer);
        groups.removeMember(MEMBER, RETAINED, peer);
        assertEquals(0L, membershipCount(RETAINED, peer));
        assertEquals(1L, membershipCount(new GroupId(GroupEntity.BASIC_ID), peer));
        assertEquals(List.of("user_group.member_change", "user_group.manager_change", "user_group.manager_change",
                "user_group.manager_change", "user_group.member_change"), actions());
    }

    @Test
    void scopedMutationIsRestrictedToOwnedGroupsAndCannotLoseOwnScope() {
        assertEquals("Renamed", groups.rename(MEMBER, RETAINED, "Renamed").name());
        assertEquals(new GroupPermissions(true, true, false, false, true),
                groups.get(MEMBER, RETAINED).permissions());
        assertEquals("IAM_GROUP_NOT_FOUND",
                assertThrows(IamException.class, () -> groups.rename(MEMBER, REMOVED, "Hidden")).code());
        assertEquals("IAM_GROUP_NOT_FOUND",
                assertThrows(IamException.class, () -> groups.addMembers(MEMBER, REMOVED, Set.of(ADMIN))).code());
        assertEquals("IAM_GROUP_NOT_FOUND",
                assertThrows(IamException.class, () -> groups.assignManager(MEMBER, REMOVED, MEMBER)).code());
        assertEquals("IAM_GROUP_NOT_FOUND",
                assertThrows(IamException.class, () -> groups.removeMember(MEMBER, REMOVED, MEMBER)).code());
        assertEquals("IAM_ACCESS_DENIED",
                assertThrows(IamException.class, () -> groups.removeManager(MEMBER, RETAINED, MEMBER)).code());
        assertEquals("IAM_ACCESS_DENIED",
                assertThrows(IamException.class, () -> groups.removeMember(MEMBER, RETAINED, MEMBER)).code());
        assertEquals("IAM_GROUP_MEMBER_NOT_FOUND",
                assertThrows(IamException.class, () -> groups.assignManager(MEMBER, RETAINED, ADMIN)).code());
        assertEquals("IAM_ACCESS_DENIED",
                assertThrows(IamException.class, () -> groups.create(MEMBER, "Forbidden")).code());
        assertEquals("IAM_ACCESS_DENIED",
                assertThrows(IamException.class, () -> groups.delete(MEMBER, RETAINED)).code());
        assertEquals("IAM_ACCESS_DENIED",
                assertThrows(IamException.class, () -> groups.replaceCapabilities(MEMBER, RETAINED, Set.of())).code());
        assertTrue(managerFlag(RETAINED));
        // The rename is recorded; each reach past the managed Group is a denial that survives its rollback;
        // refusals of a capability the manager never held are not recorded (ADR 0013).
        assertEquals(List.of("user_group.rename", "permission.denied", "permission.denied",
                "permission.denied", "permission.denied"), actions());
        assertEquals(4L, jdbc.sql("SELECT count(*) FROM audit_event WHERE outcome = 'DENIED' AND resource_id = :id")
                .param("id", REMOVED.value().toString()).query(Long.class).single());
    }

    private List<String> actions() {
        return jdbc.sql("SELECT action FROM audit_event ORDER BY occurred_at, id").query(String.class).list();
    }

    @Test
    void everyRemovalPathProtectsTheLastStandardMembershipButAllowsReplacement() {
        ActorId peer = actor("70000000-0000-0000-0000-000000000059");
        persistActor(peer, "MEMBER");
        persistMembership(RETAINED, peer, false);

        assertEquals("IAM_LAST_GROUP_PROTECTED",
                assertThrows(IamException.class, () -> groups.removeMember(MEMBER, RETAINED, peer)).code());
        assertEquals("IAM_LAST_GROUP_PROTECTED",
                assertThrows(IamException.class, () -> groups.removeMember(ADMIN, RETAINED, peer)).code());
        assertEquals("IAM_LAST_GROUP_PROTECTED",
                assertThrows(IamException.class, () -> groups.delete(ADMIN, RETAINED)).code());
        assertEquals("IAM_LAST_GROUP_PROTECTED",
                assertThrows(IamException.class, () -> groups.replaceOrdinaryMemberships(ADMIN, peer, Set.of())).code());
        assertEquals(1L, membershipCount(RETAINED, peer));

        groups.replaceOrdinaryMemberships(ADMIN, peer, Set.of(ADDED));
        assertEquals(0L, membershipCount(RETAINED, peer));
        assertEquals(1L, membershipCount(ADDED, peer));
        groups.delete(ADMIN, RETAINED);
        assertEquals(0L, membershipCount(RETAINED, MEMBER));
        groups.replaceOrdinaryMemberships(ADMIN, MEMBER, Set.of());
        assertEquals(1L, membershipCount(new GroupId(GroupEntity.BASIC_ID), MEMBER));
    }

    @Test
    void globalGroupsAdministratorCannotAmplifyGrantsButSystemAdminCan() {
        ActorId peer = actor("70000000-0000-0000-0000-000000000059");
        persistActor(peer, "MEMBER");
        persistMembership(new GroupId(GroupEntity.BASIC_ID), peer, false);
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :administrators, 'GROUPS_MANAGE'),
                               (:tenantId, :privileged, 'MODELS_MANAGE')
                        """)
                .param("tenantId", TENANT.value())
                .param("administrators", RETAINED.value())
                .param("privileged", ADDED.value()).update();

        assertEquals("IAM_MANAGER_AMPLIFICATION_DENIED",
                assertThrows(IamException.class, () -> groups.addMembers(MEMBER, ADDED, Set.of(peer))).code());
        assertEquals(0L, membershipCount(ADDED, peer));
        groups.addMembers(ADMIN, ADDED, Set.of(peer));
        assertEquals(1L, membershipCount(ADDED, peer));
        groups.addMembers(MEMBER, REMOVED, Set.of(peer));
        assertEquals(1L, membershipCount(REMOVED, peer));
    }

    private void seed() {
        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot
                        ) VALUES (:id, 'group-replace-test', 'Group replace test', 'ACTIVE', 'test', 1)
                        """)
                .param("id", TENANT.value())
                .update();
        persistActor(ADMIN, "OWNER");
        persistActor(MEMBER, "MEMBER");
        persistGroup(new GroupId(GroupEntity.ADMIN_ID), "Admin", "ADMIN");
        persistGroup(new GroupId(GroupEntity.BASIC_ID), "Basic", "BASIC");
        persistGroup(RETAINED, "Retained", null);
        persistGroup(ADDED, "Added", null);
        persistGroup(REMOVED, "Removed", null);
        jdbc.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'SYSTEM_ADMIN')
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", GroupEntity.ADMIN_ID)
                .update();
        persistMembership(new GroupId(GroupEntity.ADMIN_ID), ADMIN, false);
        persistMembership(new GroupId(GroupEntity.BASIC_ID), MEMBER, false);
        persistMembership(RETAINED, MEMBER, true);
        persistMembership(REMOVED, MEMBER, false);
    }

    private void persistActor(ActorId actorId, String role) {
        jdbc.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId.value())
                .update();
        jdbc.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, 'ACTIVE')
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", actorId.value())
                .param("role", role)
                .update();
    }

    private void persistGroup(GroupId groupId, String name, String systemKey) {
        var statement = jdbc.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name, system_key)
                        VALUES (:tenantId, :groupId, :name, :systemKey)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId.value())
                .param("name", name);
        if (systemKey == null) {
            statement.param("systemKey", null, Types.VARCHAR).update();
        } else {
            statement.param("systemKey", systemKey).update();
        }
    }

    private void persistMembership(GroupId groupId, ActorId actorId, boolean manager) {
        jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id, is_manager)
                        VALUES (:tenantId, :groupId, :actorId, :manager)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId.value())
                .param("actorId", actorId.value())
                .param("manager", manager)
                .update();
    }

    private boolean managerFlag(GroupId groupId) {
        return jdbc.sql("""
                        SELECT is_manager
                        FROM iam_group_memberships
                        WHERE tenant_id = :tenantId AND group_id = :groupId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId.value())
                .param("actorId", MEMBER.value())
                .query(Boolean.class)
                .single();
    }

    private long membershipCount(GroupId groupId, ActorId actorId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM iam_group_memberships
                        WHERE tenant_id = :tenantId AND group_id = :groupId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId.value())
                .param("actorId", actorId.value())
                .query(Long.class)
                .single();
    }

    private long authorizationVersion() {
        return jdbc.sql("SELECT authorization_version FROM tenants WHERE id = :tenantId")
                .param("tenantId", TENANT.value())
                .query(Long.class)
                .single();
    }

    private static ActorId actor(String value) {
        return new ActorId(UUID.fromString(value));
    }

    private static GroupId group(String value) {
        return new GroupId(UUID.fromString(value));
    }
}
