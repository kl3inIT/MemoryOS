package io.memoryos.iam.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.group.GroupId;
import io.memoryos.iam.group.GroupProvisioner;
import io.memoryos.iam.group.GroupSystemKey;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupEntity;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupRepository;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.group.DefaultGroupProvisioner;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresGroupProvisionerTest {
    private static final TenantId TENANT = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000060")
    );
    private static final ActorId OWNER = new ActorId(
            UUID.fromString("20000000-0000-0000-0000-000000000060")
    );
    private static final ActorId INACTIVE_MEMBER = new ActorId(
            UUID.fromString("30000000-0000-0000-0000-000000000060")
    );

    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private HikariDataSource dataSource;
    private GroupProvisioner provisioner;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        transactions = new TransactionTemplate(jpa.transactionManager());
        var target = new DefaultGroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        provisioner = TestDatabase.transactionalProxy(
                target,
                GroupProvisioner.class,
                jpa.transactionManager()
        );
        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot
                        ) VALUES (:id, 'group-provisioner-test', 'Group provisioner test', 'ACTIVE', 'test', 1)
                        """)
                .param("id", TENANT.value())
                .update();
        persistMember(OWNER, "OWNER", "ACTIVE");
        persistMember(INACTIVE_MEMBER, "MEMBER", "INACTIVE");
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
    void provisionsSystemGroupsAndBasicMembershipIdempotentlyWithoutChangingTenantMembership() {
        transactions.executeWithoutResult(_ -> provisioner.bootstrap(TENANT, OWNER));
        transactions.executeWithoutResult(_ -> provisioner.bootstrap(TENANT, OWNER));
        transactions.executeWithoutResult(_ -> provisioner.addToBasicGroup(TENANT, INACTIVE_MEMBER));
        transactions.executeWithoutResult(_ -> provisioner.addToBasicGroup(TENANT, INACTIVE_MEMBER));

        assertEquals(2L, count("iam_groups"));
        assertEquals(2L, count("iam_group_capability_grants"));
        assertEquals(1L, jdbc.sql("""
                        SELECT COUNT(*)
                        FROM iam_group_capability_grants grant_record
                        JOIN iam_groups group_record
                          ON group_record.tenant_id = grant_record.tenant_id
                         AND group_record.id = grant_record.group_id
                        WHERE group_record.tenant_id = :tenantId
                          AND group_record.system_key = 'ADMIN'
                          AND grant_record.capability = 'SYSTEM_ADMIN'
                        """)
                .param("tenantId", TENANT.value())
                .query(Long.class)
                .single());
        assertEquals("SYSTEM_BASIC", jdbc.sql("""
                        SELECT grant_record.capability
                        FROM iam_group_capability_grants grant_record
                        JOIN iam_groups group_record
                          ON group_record.tenant_id = grant_record.tenant_id
                         AND group_record.id = grant_record.group_id
                        WHERE group_record.tenant_id = :tenantId
                          AND group_record.system_key = 'BASIC'
                        """)
                .param("tenantId", TENANT.value())
                .query(String.class)
                .single());
        assertEquals(2L, membershipCount(OWNER));
        assertEquals(1L, membershipCount(INACTIVE_MEMBER));
        assertEquals(0L, adminMembershipCount());
        assertFalse(jdbc.sql("""
                        SELECT membership.is_manager
                        FROM iam_group_memberships membership
                        JOIN iam_groups group_record
                          ON group_record.tenant_id = membership.tenant_id
                         AND group_record.id = membership.group_id
                        WHERE membership.tenant_id = :tenantId
                          AND membership.actor_id = :actorId
                          AND group_record.system_key = 'BASIC'
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", INACTIVE_MEMBER.value())
                .query(Boolean.class)
                .single());
        assertEquals("INACTIVE", jdbc.sql("""
                        SELECT status
                        FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", INACTIVE_MEMBER.value())
                .query(String.class)
                .single());
    }

    @Test
    void repositoryRejectsProtectedGrantReplacementWithoutRemovingExistingAuthority() {
        transactions.executeWithoutResult(_ -> provisioner.bootstrap(TENANT, OWNER));
        var groupRepository = new GroupRepository(jpa.entityManager());
        var grantRepository = new GroupCapabilityGrantRepository(jpa.entityManager());
        var ordinaryId = new GroupId(UUID.randomUUID());
        transactions.executeWithoutResult(_ -> {
            var ordinary = new GroupEntity(groupRepository.tenantReference(TENANT),
                    ordinaryId.value(), "Ordinary", null);
            groupRepository.persist(ordinary);
            grantRepository.replace(ordinary, Set.of(IamCapability.USERS_MANAGE));
        });

        for (Set<IamCapability> requested : Set.of(
                Set.<IamCapability>of(),
                Set.of(IamCapability.SEARCH_READ),
                Set.of(IamCapability.CHAT_READ),
                Set.of(IamCapability.CHAT_WRITE),
                Set.of(IamCapability.IMAGE_GENERATE),
                Set.of(IamCapability.LLM_GATEWAY_USE),
                Set.of(IamCapability.GROUPS_READ),
                Set.of(IamCapability.SOURCES_READ),
                Set.of(IamCapability.SOURCES_DELETE),
                Set.of(IamCapability.SYSTEM_BASIC, IamCapability.USERS_MANAGE),
                Set.of(IamCapability.SYSTEM_ADMIN)
        )) {
            var failure = assertThrows(IamException.class, () -> transactions.executeWithoutResult(_ ->
                    grantRepository.replace(groupRepository.findSystem(TENANT, GroupSystemKey.BASIC)
                            .orElseThrow(), requested)));
            assertEquals(IamFailureReason.GROUP_PROTECTED.code(), failure.code());
        }
        for (IamCapability reserved : Set.of(
                IamCapability.SYSTEM_BASIC, IamCapability.SEARCH_READ, IamCapability.SYSTEM_ADMIN,
                IamCapability.CHAT_READ, IamCapability.CHAT_WRITE,
                IamCapability.IMAGE_GENERATE, IamCapability.LLM_GATEWAY_USE, IamCapability.GROUPS_READ,
                IamCapability.SOURCES_READ, IamCapability.SOURCES_DELETE
        )) {
            var failure = assertThrows(IamException.class, () -> transactions.executeWithoutResult(_ ->
                    grantRepository.replace(groupRepository.find(TENANT, ordinaryId).orElseThrow(),
                            Set.of(IamCapability.USERS_MANAGE, reserved))));
            assertEquals(IamFailureReason.GROUP_PROTECTED.code(), failure.code());
        }
        transactions.executeWithoutResult(_ -> {
            assertEquals(Set.of(IamCapability.SYSTEM_BASIC),
                    grantRepository.findCapabilities(groupRepository.findSystem(TENANT, GroupSystemKey.BASIC)
                            .orElseThrow()));
            assertEquals(Set.of(IamCapability.USERS_MANAGE),
                    grantRepository.findCapabilities(groupRepository.find(TENANT, ordinaryId).orElseThrow()));
        });
    }

    private void persistMember(ActorId actorId, String role, String status) {
        jdbc.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId.value())
                .update();
        jdbc.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, :status)
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", actorId.value())
                .param("role", role)
                .param("status", status)
                .update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT.value())
                .query(Long.class)
                .single();
    }

    private long membershipCount(ActorId actorId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM iam_group_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", actorId.value())
                .query(Long.class)
                .single();
    }

    private long adminMembershipCount() {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM iam_group_memberships membership
                        JOIN iam_groups group_record
                          ON group_record.tenant_id = membership.tenant_id
                         AND group_record.id = membership.group_id
                        WHERE membership.tenant_id = :tenantId
                          AND membership.actor_id = :actorId
                          AND group_record.system_key = 'ADMIN'
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", INACTIVE_MEMBER.value())
                .query(Long.class)
                .single();
    }
}
