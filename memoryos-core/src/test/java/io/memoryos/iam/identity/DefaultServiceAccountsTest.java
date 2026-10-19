package io.memoryos.iam.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.iam.ServiceAccounts;
import io.memoryos.iam.UserQuery;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.GroupProvisioner;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupEntity;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.persistence.JpaActorProfileRecorder;
import io.memoryos.iam.identity.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.tenant.DefaultInitialTenantBootstrapper;
import io.memoryos.iam.tenant.persistence.JpaTenantMembershipProvisioner;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.iam.user.DefaultMemberLookup;
import io.memoryos.iam.user.persistence.UserQueryRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DefaultServiceAccountsTest {
    private static final TenantId TENANT = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000246"));
    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final UUID OWNER = UUID.fromString("20000000-0000-0000-0000-000000000246");

    private JpaHarness jpa;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private JpaExternalIdentityRegistry identities;
    private JpaActorProfileRecorder profiles;
    private UserQueryRepository users;
    private ServiceAccounts accounts;
    private JpaTenantMembershipProvisioner memberships;
    private ActorId owner;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        transactions = new TransactionTemplate(jpa.transactionManager());
        var tenants = new JpaTenantRepository(jpa.entityManager());
        identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        profiles = new JpaActorProfileRecorder(jpa.entityManager());
        users = new UserQueryRepository(jdbc);
        var locks = new IamLockRepository(jdbc);
        var groups = new GroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        owner = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(tenants, locks, identities, identities, groups, event -> { }),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        ).bootstrap(new InitialTenantBootstrapRequest(
                TENANT, new ExternalIdentity(ISSUER, "owner"), "tasco", "Tasco", "TEST-MEM-246"
        )).ownerActorId();
        memberships = new JpaTenantMembershipProvisioner(tenants);
        accounts = new DefaultServiceAccounts(tenants, locks, identities, profiles, memberships, groups);
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void aServiceAccountIsAnActiveBasicMemberThatIsNoPersonOfTheTenant() {
        ActorId bot = transactions.execute(_ -> accounts.create(TENANT, OWNER, "Trợ lý Lark", "en"));

        assertEquals("SERVICE:en", jdbc.sql("SELECT account_type || ':' || ui_language FROM actors WHERE id = :actor")
                .param("actor", bot.value()).query(String.class).single());
        assertEquals(bot, identities.resolve(new ExternalIdentity(ServiceAccounts.ISSUER, OWNER.toString())).orElseThrow());
        assertEquals("MEMBER:ACTIVE", jdbc.sql("SELECT role || ':' || status FROM tenant_memberships WHERE actor_id = :actor")
                .param("actor", bot.value()).query(String.class).single());
        assertEquals("BASIC:false", jdbc.sql("""
                        SELECT g.system_key || ':' || gm.is_manager
                        FROM iam_group_memberships gm JOIN iam_groups g
                          ON g.tenant_id = gm.tenant_id AND g.id = gm.group_id
                        WHERE gm.actor_id = :actor
                        """)
                .param("actor", bot.value()).query(String.class).single());
        assertEquals("Trợ lý Lark:true", jdbc.sql(
                        "SELECT display_name || ':' || (email IS NULL) FROM actor_profiles WHERE actor_id = :actor")
                .param("actor", bot.value()).query(String.class).single());

        // What a turn checks: the authority of the Basic Group, as a person admitted by a sign-in holds it.
        var authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        assertEquals(
                Set.of(IamCapability.SYSTEM_BASIC, IamCapability.SEARCH_READ,
                        IamCapability.CHAT_READ, IamCapability.CHAT_WRITE,
                        IamCapability.IMAGE_GENERATE, IamCapability.LLM_GATEWAY_USE, IamCapability.AGENTS_CREATE),
                authorization.effectiveCapabilities(bot)
        );

        // Not a person: the people picker and the Group commands do not find it.
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

        // One account an owner.
        assertThrows(IllegalStateException.class,
                () -> transactions.executeWithoutResult(_ -> accounts.create(TENANT, OWNER, "Again", "vi")));
    }

    @Test
    void noSignInResolvesToAServiceAccount() {
        ActorId bot = transactions.execute(_ -> accounts.create(TENANT, OWNER, "Lark", "vi"));

        // A sign-in is resolved by the identity provider's issuer and subject; the account's issuer is MemoryOS's own,
        // which no provider signs for, so even the owner's id as a provider subject reaches another account.
        ActorId signedIn = transactions.execute(_ -> identities.resolveOrCreate(new ExternalIdentity(ISSUER, OWNER.toString())));
        assertNotEquals(bot, signedIn);
        assertEquals("STANDARD", jdbc.sql("SELECT account_type FROM actors WHERE id = :actor")
                .param("actor", signedIn.value()).query(String.class).single());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM external_identity_bindings WHERE actor_id = :actor")
                .param("actor", bot.value()).query(Long.class).single());
    }

    @Test
    void retiringEndsTheMembershipAndKeepsTheName() {
        ActorId bot = transactions.execute(_ -> accounts.create(TENANT, OWNER, "Lark", "vi"));

        transactions.executeWithoutResult(_ -> accounts.retire(TENANT, bot));
        transactions.executeWithoutResult(_ -> accounts.retire(TENANT, bot));

        assertEquals("INACTIVE", jdbc.sql("SELECT status FROM tenant_memberships WHERE actor_id = :actor")
                .param("actor", bot.value()).query(String.class).single());
        assertEquals("Lark", jdbc.sql("SELECT display_name FROM actor_profiles WHERE actor_id = :actor")
                .param("actor", bot.value()).query(String.class).single());
        // A person is not retired through this door.
        assertThrows(IllegalStateException.class,
                () -> transactions.executeWithoutResult(_ -> accounts.retire(TENANT, owner)));
    }

    @Test
    void aVerifiedEmailNamesItsOneActiveHolder() {
        var lookup = new DefaultMemberLookup(users);
        var ownerIdentity = new ExternalIdentity(ISSUER, "owner");
        assertEquals(Optional.empty(), lookup.activeMemberByVerifiedEmail(TENANT, "an@example.com"));

        transactions.executeWithoutResult(_ -> profiles.record(owner, ownerIdentity, "An", " An@Example.com ", false));
        assertEquals(Optional.empty(), lookup.activeMemberByVerifiedEmail(TENANT, "an@example.com"),
                "an address the provider did not verify names nobody");

        transactions.executeWithoutResult(_ -> profiles.record(owner, ownerIdentity, "An", " An@Example.com ", true));
        assertEquals(Optional.of(owner), lookup.activeMemberByVerifiedEmail(TENANT, "  AN@example.COM "));
        assertEquals(Optional.empty(), lookup.activeMemberByVerifiedEmail(TENANT, " "));

        // A second holder of the same address: it names neither.
        var second = new ExternalIdentity(ISSUER, "second");
        ActorId other = transactions.execute(_ -> identities.resolveOrCreate(second));
        transactions.executeWithoutResult(_ -> memberships.grantMember(TENANT, other));
        transactions.executeWithoutResult(_ -> profiles.record(other, second, "An 2", "an@example.com", true));
        assertEquals(Optional.empty(), lookup.activeMemberByVerifiedEmail(TENANT, "an@example.com"));

        // An inactive holder does not count.
        jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor")
                .param("actor", other.value()).update();
        assertEquals(Optional.of(owner), lookup.activeMemberByVerifiedEmail(TENANT, "an@example.com"));
    }
}
