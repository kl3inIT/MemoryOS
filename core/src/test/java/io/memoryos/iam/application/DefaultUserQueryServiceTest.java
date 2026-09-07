package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.AccountType;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamException;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.UserListItem;
import io.memoryos.iam.UserQuery;
import io.memoryos.iam.UserQueryService;
import io.memoryos.iam.UserSort;
import io.memoryos.iam.UserStatus;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.UserQueryRepository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DefaultUserQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final TenantId TENANT = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000055")
    );
    private static final ActorId OWNER = actor("00000000-0000-0000-0000-000000000011");
    private static final ActorId VERIFIED_MEMBER = actor("00000000-0000-0000-0000-000000000012");
    private static final ActorId PROFILELESS_MEMBER = actor("00000000-0000-0000-0000-000000000013");
    private static final ActorId UNVERIFIED_MEMBER = actor("00000000-0000-0000-0000-000000000014");
    private static final GroupId ADMIN_GROUP = group("00000000-0000-0000-0000-000000000001");
    private static final GroupId BASIC_GROUP = group("00000000-0000-0000-0000-000000000002");
    private static final GroupId RESEARCH_GROUP = group("30000000-0000-0000-0000-000000000055");
    private static final UUID SUPPRESSED_INVITATION = uuid("40000000-0000-0000-0000-000000000001");
    private static final UUID PRESENTED_INVITATION = uuid("40000000-0000-0000-0000-000000000002");
    private static final UUID EXPIRED_INVITATION = uuid("40000000-0000-0000-0000-000000000003");

    private JdbcClient jdbcClient;
    private JpaHarness jpa;
    private UserQueryService users;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        seedUsers();
        var authorization = new DefaultIamAuthorization(
                new IamAuthorizationRepository(jdbcClient),
                new IamLockRepository(jdbcClient)
        );
        users = TestDatabase.transactionalProxy(
                new DefaultUserQueryService(
                        new UserQueryRepository(jdbcClient),
                        authorization,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                ),
                UserQueryService.class,
                jpa.transactionManager()
        );
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void presentsAuthoritativeRowsWithAccountTypeGroupsAndExactProfileProvenance() {
        var page = users.list(
                OWNER,
                new UserQuery(null, null, null, null, UserSort.NAME_ASC, 0, 100)
        );

        assertEquals(5L, page.totalItems());
        assertEquals(3L, page.counts().active());
        assertEquals(1L, page.counts().inactive());
        assertEquals(1L, page.counts().invited());
        assertFalse(page.items().stream().anyMatch(entry -> SUPPRESSED_INVITATION.equals(entry.invitationId())));
        assertTrue(page.items().stream().anyMatch(entry -> PRESENTED_INVITATION.equals(entry.invitationId())));
        assertFalse(page.items().stream().anyMatch(entry -> EXPIRED_INVITATION.equals(entry.invitationId())));

        UserListItem verified = item(page.items(), VERIFIED_MEMBER);
        assertEquals(AccountType.STANDARD, verified.accountType());
        assertEquals("https://issuer.example", verified.profileIssuer());
        assertEquals(
                List.of(BASIC_GROUP, RESEARCH_GROUP),
                verified.groups().stream().map(GroupIdentity::id).toList()
        );
        assertEquals(GroupSystemKey.BASIC, verified.groups().getFirst().systemKey());
        assertNull(verified.groups().get(1).systemKey());

        UserListItem profileless = item(page.items(), PROFILELESS_MEMBER);
        assertNull(profileless.displayName());
        assertNull(profileless.email());
        assertNull(profileless.emailVerified());
        assertNull(profileless.profileIssuer());
        assertEquals(List.of(BASIC_GROUP), profileless.groups().stream().map(GroupIdentity::id).toList());

        UserListItem invited = page.items().stream()
                .filter(entry -> PRESENTED_INVITATION.equals(entry.invitationId()))
                .findFirst()
                .orElseThrow();
        assertNull(invited.accountType());
        assertEquals(List.of(), invited.groups());
    }

    @Test
    void filtersByRealGroupWithoutChangingGlobalCounts() {
        var filtered = users.list(
                OWNER,
                new UserQuery(null, null, null, RESEARCH_GROUP, UserSort.NAME_ASC, 0, 20)
        );

        assertEquals(1L, filtered.totalItems());
        assertEquals(VERIFIED_MEMBER, filtered.items().getFirst().actorId());
        assertEquals(3L, filtered.counts().active());
        assertEquals(1L, filtered.counts().inactive());
        assertEquals(1L, filtered.counts().invited());

        var outOfRange = users.list(
                OWNER,
                new UserQuery(null, null, null, null, UserSort.NAME_ASC, 99, 20)
        );
        assertEquals(List.of(), outOfRange.items());
        assertEquals(5L, outOfRange.totalItems());
    }

    @Test
    void preservesSearchStatusRoleAndStablePagination() {
        var searchPage = users.list(
                OWNER,
                new UserQuery("  PENDING@EXAMPLE.COM ", null, null, null, UserSort.EMAIL_ASC, 0, 20)
        );
        assertEquals(2L, searchPage.totalItems());
        assertTrue(searchPage.items().stream().anyMatch(entry -> UNVERIFIED_MEMBER.equals(entry.actorId())));
        assertTrue(searchPage.items().stream().anyMatch(entry -> PRESENTED_INVITATION.equals(entry.invitationId())));

        var contradictoryPage = users.list(
                OWNER,
                new UserQuery(
                        null,
                        UserStatus.INVITED,
                        TenantMembershipRole.MEMBER,
                        null,
                        UserSort.STATUS_ASC,
                        0,
                        20
                )
        );
        assertEquals(List.of(), contradictoryPage.items());
        assertEquals(0L, contradictoryPage.totalItems());
        assertEquals(searchPage.counts(), contradictoryPage.counts());

        var first = users.list(
                OWNER,
                new UserQuery(null, null, null, null, UserSort.NAME_ASC, 0, 1)
        );
        var second = users.list(
                OWNER,
                new UserQuery(null, null, null, null, UserSort.NAME_ASC, 1, 1)
        );
        assertEquals(VERIFIED_MEMBER, first.items().getFirst().actorId());
        assertEquals(UNVERIFIED_MEMBER, second.items().getFirst().actorId());
    }

    @Test
    void requiresFreshUsersManageCapability() {
        assertThrows(IamException.class, () -> users.list(VERIFIED_MEMBER, UserQuery.defaults()));
        jdbcClient.sql("""
                        UPDATE tenant_memberships
                        SET status = 'INACTIVE'
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", OWNER.value())
                .update();
        assertThrows(IamException.class, () -> users.list(OWNER, UserQuery.defaults()));
    }

    private void seedUsers() {
        persistActor(OWNER, "owner-subject");
        persistActor(VERIFIED_MEMBER, "verified-subject");
        persistActor(PROFILELESS_MEMBER, "profileless-subject");
        persistActor(UNVERIFIED_MEMBER, "unverified-subject");
        jdbcClient.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot
                        ) VALUES (:tenantId, 'tasco', 'Tasco', 'ACTIVE', 'TEST-MEM-55', 1)
                        """)
                .param("tenantId", TENANT.value())
                .update();
        persistMembership(OWNER, "OWNER", "ACTIVE");
        persistMembership(VERIFIED_MEMBER, "MEMBER", "ACTIVE");
        persistMembership(PROFILELESS_MEMBER, "MEMBER", "INACTIVE");
        persistMembership(UNVERIFIED_MEMBER, "MEMBER", "ACTIVE");
        persistGroups();
        persistProfile(OWNER, "owner-subject", "Zed Owner", "shared@example.com", true);
        persistProfile(VERIFIED_MEMBER, "verified-subject", "Alex", "shared@example.com", true);
        persistProfile(UNVERIFIED_MEMBER, "unverified-subject", null, "pending@example.com", false);
        persistPendingInvitation(SUPPRESSED_INVITATION, "shared@example.com", NOW.plusSeconds(3600));
        persistPendingInvitation(PRESENTED_INVITATION, "pending@example.com", NOW.plusSeconds(3600));
        persistPendingInvitation(EXPIRED_INVITATION, "expired@example.com", NOW.minusSeconds(1));
        persistExpiredInvitation(uuid("40000000-0000-0000-0000-000000000004"));
    }

    private void persistGroups() {
        persistGroup(ADMIN_GROUP, "Admin", "ADMIN");
        persistGroup(BASIC_GROUP, "Basic", "BASIC");
        persistGroup(RESEARCH_GROUP, "Research", null);
        jdbcClient.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'IAM_ADMIN')
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", ADMIN_GROUP.value())
                .update();
        persistGroupMembership(OWNER, ADMIN_GROUP);
        persistGroupMembership(OWNER, BASIC_GROUP);
        persistGroupMembership(VERIFIED_MEMBER, BASIC_GROUP);
        persistGroupMembership(VERIFIED_MEMBER, RESEARCH_GROUP);
        persistGroupMembership(PROFILELESS_MEMBER, BASIC_GROUP);
        persistGroupMembership(UNVERIFIED_MEMBER, BASIC_GROUP);
    }

    private void persistGroup(GroupId groupId, String name, String systemKey) {
        jdbcClient.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name, system_key)
                        VALUES (:tenantId, :groupId, :name, :systemKey)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId.value())
                .param("name", name)
                .param("systemKey", systemKey)
                .update();
    }

    private void persistGroupMembership(ActorId actorId, GroupId groupId) {
        jdbcClient.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
                        VALUES (:tenantId, :groupId, :actorId)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", groupId.value())
                .param("actorId", actorId.value())
                .update();
    }

    private void persistActor(ActorId actorId, String subject) {
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO external_identity_bindings (issuer, subject, actor_id)
                        VALUES ('https://issuer.example', :subject, :actorId)
                        """)
                .param("subject", subject)
                .param("actorId", actorId.value())
                .update();
    }

    private void persistProfile(
            ActorId actorId,
            String subject,
            String displayName,
            String email,
            boolean verified
    ) {
        jdbcClient.sql("""
                        INSERT INTO actor_profiles (
                            actor_id, issuer, subject, display_name, email, email_verified, observed_at
                        ) VALUES (
                            :actorId, 'https://issuer.example', :subject, :displayName, :email, :verified, :now
                        )
                        """)
                .param("actorId", actorId.value())
                .param("subject", subject)
                .param("displayName", displayName)
                .param("email", email)
                .param("verified", verified)
                .param("now", Timestamp.from(NOW))
                .update();
    }

    private void persistMembership(ActorId actorId, String role, String status) {
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, :status)
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", actorId.value())
                .param("role", role)
                .param("status", status)
                .update();
    }

    private void persistPendingInvitation(UUID invitationId, String email, Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO tenant_invitations (
                            id, tenant_id, normalized_email, open_email_key, secret_digest,
                            status, created_by_actor_id, expires_at
                        ) VALUES (
                            :invitationId, :tenantId, :email, :email, :digest,
                            'PENDING', :ownerId, :expiresAt
                        )
                        """)
                .param("invitationId", invitationId)
                .param("tenantId", TENANT.value())
                .param("email", email)
                .param("digest", invitationId.toString())
                .param("ownerId", OWNER.value())
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    private void persistExpiredInvitation(UUID invitationId) {
        jdbcClient.sql("""
                        INSERT INTO tenant_invitations (
                            id, tenant_id, normalized_email, open_email_key, secret_digest,
                            status, created_by_actor_id, expires_at
                        ) VALUES (
                            :invitationId, :tenantId, 'history@example.com', NULL, :digest,
                            'EXPIRED', :ownerId, :expiresAt
                        )
                        """)
                .param("invitationId", invitationId)
                .param("tenantId", TENANT.value())
                .param("digest", invitationId.toString())
                .param("ownerId", OWNER.value())
                .param("expiresAt", Timestamp.from(NOW.minusSeconds(60)))
                .update();
    }

    private static UserListItem item(List<UserListItem> items, ActorId actorId) {
        return items.stream().filter(item -> actorId.equals(item.actorId())).findFirst().orElseThrow();
    }

    private static ActorId actor(String value) {
        return new ActorId(uuid(value));
    }

    private static GroupId group(String value) {
        return new GroupId(uuid(value));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
