package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupAdministrationGuard;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupService;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamException;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.persistence.GroupEntity;
import io.memoryos.iam.persistence.GroupInvariantRepository;
import io.memoryos.iam.persistence.GroupMembershipRepository;
import io.memoryos.iam.persistence.GroupProjectionRepository;
import io.memoryos.iam.persistence.GroupRepository;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;

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
    private GroupService groups;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
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
                new DefaultGroupAdministrationGuard(new GroupInvariantRepository(jdbc)),
                GroupAdministrationGuard.class,
                transactionManager
        );
        GroupService target = new DefaultGroupService(
                authorization,
                locks,
                new GroupRepository(entityManager),
                new GroupMembershipRepository(entityManager),
                new GroupCapabilityGrantRepository(entityManager),
                new GroupProjectionRepository(jdbc),
                new GroupInvariantRepository(jdbc),
                administrationGuard
        );
        groups = TestDatabase.transactionalProxy(target, GroupService.class, transactionManager);
        seed();
    }

    @AfterEach
    void closeEntityManagerFactory() {
        jpa.close();
    }

    @Test
    void ordinaryMembershipReplacementPreservesSystemEdgesAndRetainedManagerFlags() {
        groups.replaceOrdinaryMemberships(ADMIN, MEMBER, Set.of(RETAINED, ADDED));

        assertTrue(managerFlag(RETAINED));
        assertFalse(managerFlag(ADDED));
        assertEquals(0L, membershipCount(REMOVED, MEMBER));
        assertEquals(1L, membershipCount(new GroupId(GroupEntity.BASIC_ID), MEMBER));
        assertEquals(1L, authorizationVersion());
    }

    @Test
    void scopedManagerCannotRemoveAnotherManagerButGlobalAdministrationCan() {
        ActorId otherManager = actor("70000000-0000-0000-0000-000000000059");
        persistActor(otherManager, "MEMBER");
        persistMembership(RETAINED, otherManager, true);

        IamException failure = assertThrows(
                IamException.class,
                () -> groups.removeMember(MEMBER, RETAINED, otherManager)
        );

        assertEquals("IAM_ACCESS_DENIED", failure.code());
        assertEquals(1L, membershipCount(RETAINED, otherManager));
        groups.removeMember(ADMIN, RETAINED, otherManager);
        assertEquals(0L, membershipCount(RETAINED, otherManager));
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
                        VALUES (:tenantId, :groupId, 'IAM_ADMIN')
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
            statement.param("systemKey", null, java.sql.Types.VARCHAR).update();
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
