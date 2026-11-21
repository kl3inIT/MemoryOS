package io.memoryos.iam.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.iam.UserQuery;
import io.memoryos.iam.group.GroupProvisioner;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupEntity;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.tenant.DefaultInitialTenantBootstrapper;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.iam.user.persistence.UserQueryRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The service accounts the chat bots created (MEM-246) stay after the bots' removal (ADR 0032), as the actors their
 * conversations and audit events name. Nothing creates one any more, so the account is written as the bots wrote it.
 */
@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ServiceAccountTest {
    private static final TenantId TENANT = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000246"));
    private static final String ISSUER = "https://keycloak.example/realms/memoryos";

    private JpaHarness jpa;
    private JdbcClient jdbc;
    private ActorId owner;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        var tenants = new JpaTenantRepository(jpa.entityManager());
        var identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        var groups = new GroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        owner = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(tenants, new IamLockRepository(jdbc), identities, identities, groups,
                        event -> { }),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        ).bootstrap(new InitialTenantBootstrapRequest(
                TENANT, new ExternalIdentity(ISSUER, "owner"), "tasco", "Tasco", "TEST-ADR-0032"
        )).ownerActorId();
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void anAccountABotLeftIsNoPersonOfTheTenant() {
        var bot = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id, account_type) VALUES (:id, 'SERVICE')").param("id", bot.value()).update();
        jdbc.sql("""
                        INSERT INTO tenant_memberships(tenant_id, actor_id, role, status)
                        VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')
                        """)
                .param("tenant", TENANT.value()).param("actor", bot.value()).update();
        jdbc.sql("""
                        INSERT INTO external_identity_bindings(issuer, subject, actor_id)
                        VALUES ('urn:memoryos:service', CAST(:actor AS TEXT), :actor)
                        """)
                .param("actor", bot.value()).update();
        jdbc.sql("""
                        INSERT INTO actor_profiles(actor_id, issuer, subject, display_name, email, email_verified, observed_at)
                        VALUES (:actor, 'urn:memoryos:service', CAST(:actor AS TEXT), 'Trợ lý Lark', NULL, FALSE,
                                CURRENT_TIMESTAMP)
                        """)
                .param("actor", bot.value()).update();
        jdbc.sql("""
                        INSERT INTO iam_group_memberships(tenant_id, group_id, actor_id)
                        VALUES (:tenant, :group, :actor)
                        """)
                .param("tenant", TENANT.value()).param("group", GroupEntity.BASIC_ID).param("actor", bot.value())
                .update();

        // The people picker and the Group commands do not find it.
        var users = new UserQueryRepository(jdbc);
        assertTrue(users.searchActiveMembers(TENANT, null, 50).stream().noneMatch(person -> person.actorId().equals(bot)));
        assertEquals(Set.of(owner), new GroupInvariantRepository(jdbc).existingTenantMembers(TENANT, Set.of(owner, bot)));

        // Nor do the Users list and its counts, a Group's members and its candidates.
        var page = users.findPage(TENANT, UserQuery.defaults(), Instant.now());
        assertEquals(1L, page.totalItems());
        assertEquals(1L, page.counts().active());
        var projections = new GroupProjectionRepository(jdbc);
        var everyone = new GroupQuery(null, 0, 20);
        assertEquals(1L, projections.members(TENANT, new GroupId(GroupEntity.BASIC_ID), everyone).totalItems());
        assertEquals(0L, projections.candidates(TENANT, new GroupId(GroupEntity.ADMIN_ID), everyone).totalItems());
    }
}
