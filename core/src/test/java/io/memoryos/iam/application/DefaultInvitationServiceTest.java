package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.GroupProvisioner;
import io.memoryos.iam.IdentityProvisioningException;
import io.memoryos.iam.IdentityProvisioningFailureReason;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.iam.InvitationAcceptance;
import io.memoryos.iam.InvitationDelivery;
import io.memoryos.iam.InvitationException;
import io.memoryos.iam.InvitationFailureReason;
import io.memoryos.iam.InvitationQuery;
import io.memoryos.iam.InvitationService;
import io.memoryos.iam.InvitationSort;
import io.memoryos.iam.InvitationStatus;
import io.memoryos.iam.KeycloakRecipientProvisioning;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.VerifiedEmailInvitationAcceptance;
import io.memoryos.iam.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.persistence.GroupMembershipRepository;
import io.memoryos.iam.persistence.GroupRepository;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.InvitationQueryRepository;
import io.memoryos.iam.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.persistence.JpaInvitationRepository;
import io.memoryos.iam.persistence.JpaTenantMembershipProvisioner;
import io.memoryos.iam.persistence.JpaTenantRepository;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DefaultInvitationServiceTest {

    private static final TenantId TENANT_ID = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000012")
    );
    private static final String ISSUER = "https://keycloak.example/realms/memoryos";

    private JdbcClient jdbcClient;
    private JpaHarness jpa;
    private JpaTenantRepository tenants;
    private JpaExternalIdentityRegistry identities;
    private IamLockRepository locks;
    private DefaultGroupProvisioner groups;
    private DefaultIamAuthorization authorization;
    private MutableClock clock;
    private ActorId ownerActorId;
    private InvitationService invitations;
    private RuntimeException provisioningFailure;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        tenants = new JpaTenantRepository(jpa.entityManager());
        identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        locks = new IamLockRepository(jdbcClient);
        groups = new DefaultGroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        InitialTenantBootstrapper bootstrapper = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(
                        tenants,
                        locks,
                        identities,
                        identities,
                        groups
                ),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        );
        ownerActorId = bootstrapper.bootstrap(new InitialTenantBootstrapRequest(
                TENANT_ID,
                new ExternalIdentity(ISSUER, "owner"),
                "tasco",
                "Tasco",
                "TEST-MEM-36"
        )).ownerActorId();
        authorization = new DefaultIamAuthorization(
                new IamAuthorizationRepository(jdbcClient),
                locks
        );
        clock = new MutableClock(Instant.parse("2026-09-06T10:00:00Z"));
        invitations = invitationService(groups);
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    @Test
    void issuesAndListsDigestOnlyInvitationAfterProviderWorkCompletesOutsideTheWriteTransaction() {
        assertEquals(0L, authorizationVersion());
        var issued = invitations.issue(ownerActorId, "  Member@Example.COM ");

        assertEquals("member@example.com", issued.invitation().email());
        assertEquals(InvitationDelivery.EXISTING_ACCOUNT, issued.delivery());
        assertNotNull(issued.plaintextSecret());
        String digest = jdbcClient.sql("SELECT secret_digest FROM tenant_invitations")
                .query(String.class)
                .single();
        assertEquals(64, digest.length());
        assertNotEquals(issued.plaintextSecret(), digest);
        assertEquals(1L, authorizationVersion());

        var page = invitations.list(ownerActorId, InvitationQuery.defaults());
        assertEquals(1L, page.totalItems());
        assertEquals(issued.invitation().id(), page.items().getFirst().id());
    }

    @Test
    void providerFailureOccursBeforeAnyInvitationWriteOrExclusiveAuthorityLock() {
        provisioningFailure = new IdentityProvisioningException(
                IdentityProvisioningFailureReason.PROVIDER_UNAVAILABLE,
                "provider timed out"
        );

        assertThrows(IdentityProvisioningException.class, () -> invitations.issue(
                ownerActorId,
                "member@example.com"
        ));
        assertEquals(0L, count("tenant_invitations"));
        assertEquals(0L, authorizationVersion());
    }

    @Test
    void filtersHistoryAndRotatesRevokesOrExpiresOnlyPendingCapabilities() {
        invitations.issue(ownerActorId, "zeta@example.com");
        clock.advance(Duration.ofSeconds(1));
        var alpha = invitations.issue(ownerActorId, "alpha@example.com");
        var rotated = invitations.rotate(ownerActorId, alpha.invitation().id());
        assertNotEquals(alpha.plaintextSecret(), rotated.plaintextSecret());
        assertThrows(InvitationException.class, () -> invitations.intake(alpha.plaintextSecret()));

        var filtered = invitations.list(ownerActorId, new InvitationQuery(
                InvitationStatus.PENDING,
                "alpha",
                InvitationSort.EMAIL_ASC,
                0,
                20
        ));
        assertEquals(1L, filtered.totalItems());
        assertEquals("alpha@example.com", filtered.items().getFirst().email());

        invitations.revoke(ownerActorId, alpha.invitation().id());
        assertThrows(InvitationException.class, () -> invitations.intake(rotated.plaintextSecret()));
        assertEquals("REVOKED", invitationStatus(alpha.invitation().id()));

        var expiring = invitations.issue(ownerActorId, "expired@example.com");
        clock.advance(Duration.ofHours(73));
        var replacement = invitations.issue(ownerActorId, "expired@example.com");
        assertNotEquals(expiring.invitation().id(), replacement.invitation().id());
        assertEquals("EXPIRED", invitationStatus(expiring.invitation().id()));
    }

    @Test
    void acceptsVerifiedIdentityAtomicallyAndAddsOnlyBasicMembership() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        ActorId member = invitations.accept(new InvitationAcceptance(
                continuation.invitationId(),
                continuation.tenantId(),
                new ExternalIdentity(ISSUER, "member-subject"),
                "MEMBER@example.com",
                true
        ));

        assertEquals("ACCEPTED", invitationStatus(issued.invitation().id()));
        assertEquals(1L, jdbcClient.sql("""
                        SELECT COUNT(*) FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                          AND role = 'MEMBER' AND status = 'ACTIVE'
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", member.value())
                .query(Long.class)
                .single());
        assertEquals(1L, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM iam_group_memberships membership
                        JOIN iam_groups iam_group
                          ON iam_group.tenant_id = membership.tenant_id
                         AND iam_group.id = membership.group_id
                        WHERE membership.tenant_id = :tenantId
                          AND membership.actor_id = :actorId
                          AND iam_group.system_key = 'BASIC'
                          AND membership.is_manager = FALSE
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", member.value())
                .query(Long.class)
                .single());
        assertEquals(0L, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM iam_group_memberships membership
                        JOIN iam_groups iam_group
                          ON iam_group.tenant_id = membership.tenant_id
                         AND iam_group.id = membership.group_id
                        WHERE membership.tenant_id = :tenantId
                          AND membership.actor_id = :actorId
                          AND iam_group.system_key = 'ADMIN'
                        """)
                .param("tenantId", TENANT_ID.value())
                .param("actorId", member.value())
                .query(Long.class)
                .single());
    }

    @Test
    void acceptsTheUniqueVerifiedEmailInvitationWithoutContinuation() {
        invitations.issue(ownerActorId, "member@example.com");

        ActorId member = invitations.acceptVerifiedEmail(new VerifiedEmailInvitationAcceptance(
                new ExternalIdentity(ISSUER, "member-subject"),
                "member@example.com",
                true
        ));

        assertEquals(2L, count("actors"));
        assertEquals(member.value(), jdbcClient.sql("SELECT accepted_by_actor_id FROM tenant_invitations")
                .query(UUID.class)
                .single());
    }

    @Test
    void rejectsUnverifiedMismatchedOrAlreadyAuthorizedIdentitiesWithoutPartialWrites() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        ExternalIdentity memberIdentity = new ExternalIdentity(ISSUER, "member-subject");

        InvitationException unverified = assertThrows(InvitationException.class, () -> invitations.accept(
                new InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.tenantId(),
                        memberIdentity,
                        "member@example.com",
                        false
                )
        ));
        assertEquals(InvitationFailureReason.EMAIL_NOT_VERIFIED, unverified.reason());
        InvitationException mismatch = assertThrows(InvitationException.class, () -> invitations.accept(
                new InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.tenantId(),
                        memberIdentity,
                        "other@example.com",
                        true
                )
        ));
        assertEquals(InvitationFailureReason.EMAIL_MISMATCH, mismatch.reason());
        assertEquals(1L, count("actors"));
        assertEquals("PENDING", invitationStatus(issued.invitation().id()));

        InvitationException ownerConflict = assertThrows(InvitationException.class, () -> invitations.accept(
                new InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.tenantId(),
                        new ExternalIdentity(ISSUER, "owner"),
                        "member@example.com",
                        true
                )
        ));
        assertEquals(InvitationFailureReason.IDENTITY_CONFLICT, ownerConflict.reason());
        assertEquals("PENDING", invitationStatus(issued.invitation().id()));
    }

    @Test
    void rollsBackActorBindingMembershipAndGroupEdgeWhenBasicProvisioningFails() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        GroupProvisioner failingGroups = new GroupProvisioner() {
            @Override
            public void bootstrap(TenantId tenantId, ActorId configuredOwner) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void addToBasicGroup(TenantId tenantId, ActorId actorId) {
                throw new IllegalStateException("Basic group write failed");
            }
        };
        InvitationService failingService = invitationService(failingGroups);

        assertThrows(IllegalStateException.class, () -> failingService.accept(new InvitationAcceptance(
                continuation.invitationId(),
                continuation.tenantId(),
                new ExternalIdentity(ISSUER, "member-subject"),
                "member@example.com",
                true
        )));
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(1L, count("tenant_memberships"));
        assertEquals(2L, count("iam_group_memberships"));
        assertEquals("PENDING", invitationStatus(issued.invitation().id()));
    }

    @Test
    void expiryDuringAcceptanceReturnsNotAvailableAndRollsBackNewAuthority() {
        var issued = invitations.issue(ownerActorId, "expiring@example.com");
        GroupProvisioner expiringGroups = new GroupProvisioner() {
            @Override
            public void bootstrap(TenantId tenantId, ActorId configuredOwner) {
                groups.bootstrap(tenantId, configuredOwner);
            }

            @Override
            public void addToBasicGroup(TenantId tenantId, ActorId actorId) {
                groups.addToBasicGroup(tenantId, actorId);
                clock.advance(Duration.between(clock.instant(), issued.invitation().expiresAt()));
            }
        };
        InvitationService expiringService = invitationService(expiringGroups);

        InvitationException failure = assertThrows(
                InvitationException.class,
                () -> expiringService.accept(new InvitationAcceptance(
                        issued.invitation().id(),
                        TENANT_ID,
                        new ExternalIdentity(ISSUER, "expiring-member"),
                        "expiring@example.com",
                        true
                ))
        );

        assertEquals(InvitationFailureReason.NOT_AVAILABLE, failure.reason());
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(1L, count("tenant_memberships"));
        assertEquals(2L, count("iam_group_memberships"));
    }

    private InvitationService invitationService(GroupProvisioner groupProvisioner) {
        var membershipProvisioner = new JpaTenantMembershipProvisioner(tenants);
        var service = new DefaultInvitationService(
                new JpaInvitationRepository(jpa.entityManager()),
                new InvitationQueryRepository(jdbcClient),
                identities,
                membershipProvisioner,
                groupProvisioner,
                (_, _) -> {
                    if (provisioningFailure != null) {
                        throw provisioningFailure;
                    }
                    return KeycloakRecipientProvisioning.EXISTING_VERIFIED;
                },
                authorization,
                locks,
                new TransactionTemplate(jpa.transactionManager()),
                clock,
                Duration.ofHours(72)
        );
        return TestDatabase.transactionalProxy(
                service,
                InvitationService.class,
                jpa.transactionManager()
        );
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long authorizationVersion() {
        return jdbcClient.sql("SELECT authorization_version FROM tenants WHERE id = :tenantId")
                .param("tenantId", TENANT_ID.value())
                .query(Long.class)
                .single();
    }

    private String invitationStatus(UUID invitationId) {
        return jdbcClient.sql("SELECT status FROM tenant_invitations WHERE id = :invitationId")
                .param("invitationId", invitationId)
                .query(String.class)
                .single();
    }
}
