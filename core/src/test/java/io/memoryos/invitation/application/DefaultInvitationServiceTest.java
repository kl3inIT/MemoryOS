package io.memoryos.invitation.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.IdentityProvisioningException;
import io.memoryos.identity.IdentityProvisioningFailureReason;
import io.memoryos.identity.KeycloakRecipientProvisioning;
import io.memoryos.identity.persistence.JdbcExternalIdentityRegistrar;
import io.memoryos.identity.persistence.JdbcExternalIdentityResolver;
import io.memoryos.invitation.InvitationAcceptance;
import io.memoryos.invitation.InvitationFailureReason;
import io.memoryos.invitation.InvitationQuery;
import io.memoryos.invitation.InvitationSort;
import io.memoryos.invitation.InvitationDelivery;
import io.memoryos.invitation.VerifiedEmailInvitationAcceptance;
import io.memoryos.invitation.InvitationStatus;
import io.memoryos.invitation.InvitationView;
import io.memoryos.invitation.persistence.JdbcInvitationRepository;
import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationService;
import io.memoryos.tenant.InitialTenantBootstrapRequest;
import io.memoryos.tenant.InitialTenantBootstrapper;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.TenantMembershipProvisioner;
import io.memoryos.tenant.application.DefaultInitialTenantBootstrapper;
import io.memoryos.tenant.persistence.JdbcTenantAccessResolver;
import io.memoryos.tenant.persistence.JdbcTenantBootstrapRepository;
import io.memoryos.tenant.persistence.JdbcTenantMembershipProvisioner;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DefaultInvitationServiceTest {

    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");

    private JdbcClient jdbcClient;
    private Connection keepAlive;
    private MutableClock clock;
    private InvitationService invitations;
    private InitialTenantBootstrapper bootstrapper;
    private ActorId ownerActorId;
    private List<String> provisionedEmails;
    private RuntimeException provisioningFailure;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        try {
            keepAlive = dataSource.getConnection();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to keep the in-memory database open", exception);
        }
        TestDatabase.migrations().populate(keepAlive);

        jdbcClient = JdbcClient.create(dataSource);
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        var resolver = new JdbcExternalIdentityResolver(jdbcClient);
        var registrar = TestDatabase.transactionalProxy(
                new JdbcExternalIdentityRegistrar(jdbcClient, resolver),
                ExternalIdentityRegistrar.class,
                transactionManager
        );
        var bootstrapRepository = new JdbcTenantBootstrapRepository(jdbcClient);
        bootstrapper = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(bootstrapRepository, resolver, registrar),
                InitialTenantBootstrapper.class,
                transactionManager
        );
        ownerActorId = bootstrapper.bootstrap(bootstrapRequest()).ownerActorId();
        var membershipProvisioner = TestDatabase.transactionalProxy(
                new JdbcTenantMembershipProvisioner(jdbcClient),
                TenantMembershipProvisioner.class,
                transactionManager
        );
        provisionedEmails = new ArrayList<>();
        provisioningFailure = null;
        clock = new MutableClock(START);
        invitations = TestDatabase.transactionalProxy(
                new DefaultInvitationService(
                        new JdbcInvitationRepository(jdbcClient),
                        registrar,
                        new JdbcTenantAccessResolver(jdbcClient),
                        membershipProvisioner,
                        (email, expiresAt) -> {
                            if (provisioningFailure != null) {
                                throw provisioningFailure;
                            }
                            provisionedEmails.add(email);
                            assertTrue(expiresAt.isAfter(clock.instant()));
                            return KeycloakRecipientProvisioning.ACTIVATION_EMAIL_SENT;
                        },
                        clock,
                        Duration.ofHours(72)
                ),
                InvitationService.class,
                transactionManager
        );
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keepAlive.close();
    }

    @Test
    void issuesAndListsDigestOnlyInvitationForTheActiveOwner() {
        var issued = invitations.issue(ownerActorId, "  Member@Example.COM ");

        assertEquals("member@example.com", issued.invitation().email());
        assertEquals(InvitationStatus.PENDING, issued.invitation().status());
        assertEquals(43, issued.plaintextSecret().length());
        assertEquals(START.plus(Duration.ofHours(72)), issued.invitation().expiresAt());
        assertEquals(issued.invitation(), invitations.list(ownerActorId, InvitationQuery.defaults()).items().getFirst());
        assertNotEquals(issued.plaintextSecret(), scalar("secret_digest"));
        assertFalse(databaseContains(issued.plaintextSecret()));
        assertEquals("member@example.com", scalar("open_email_key"));
        assertEquals(InvitationDelivery.ACTIVATION_EMAIL_SENT, issued.delivery());
        assertEquals(List.of("member@example.com"), provisionedEmails);
    }

    @Test
    void rollsBackInvitationWhenRecipientProvisioningFails() {
        provisioningFailure = new IdentityProvisioningException(
                IdentityProvisioningFailureReason.PROVIDER_UNAVAILABLE,
                "provider timed out"
        );

        IdentityProvisioningException failure = assertThrows(
                IdentityProvisioningException.class,
                () -> invitations.issue(ownerActorId, "member@example.com")
        );

        assertEquals(IdentityProvisioningFailureReason.PROVIDER_UNAVAILABLE, failure.reason());
        assertEquals(0L, count("tenant_invitations"));
        assertEquals(List.of(), provisionedEmails);
    }

    @Test
    void filtersSortsAndPaginatesInvitationHistory() {
        invitations.issue(ownerActorId, "zeta@example.com");
        clock.advance(Duration.ofSeconds(1));
        var revoked = invitations.issue(ownerActorId, "alpha@example.com");
        invitations.revoke(ownerActorId, revoked.invitation().id());
        clock.advance(Duration.ofSeconds(1));
        invitations.issue(ownerActorId, "beta@example.com");

        var firstPendingPage = invitations.list(
                ownerActorId,
                new InvitationQuery(InvitationStatus.PENDING, "@EXAMPLE.COM", InvitationSort.EMAIL_ASC, 0, 1)
        );
        var secondPendingPage = invitations.list(
                ownerActorId,
                new InvitationQuery(InvitationStatus.PENDING, "@example.com", InvitationSort.EMAIL_ASC, 1, 1)
        );
        var revokedPage = invitations.list(
                ownerActorId,
                new InvitationQuery(InvitationStatus.REVOKED, "ALPHA", InvitationSort.CREATED_AT_DESC, 0, 20)
        );
        var outOfRangePage = invitations.list(
                ownerActorId,
                new InvitationQuery(null, null, InvitationSort.CREATED_AT_DESC, 10, 20)
        );

        assertEquals(2L, firstPendingPage.totalItems());
        assertEquals(2L, firstPendingPage.totalPages());
        assertEquals("beta@example.com", firstPendingPage.items().getFirst().email());
        assertEquals("zeta@example.com", secondPendingPage.items().getFirst().email());
        assertEquals(1L, revokedPage.totalItems());
        assertEquals("alpha@example.com", revokedPage.items().getFirst().email());
        assertTrue(outOfRangePage.items().isEmpty());
        assertEquals(3L, outOfRangePage.totalItems());
        assertEquals(1L, outOfRangePage.totalPages());
    }

    @Test
    void usesInvitationIdAsTheStableTieBreakerForEqualSortValues() {
        invitations.issue(ownerActorId, "first@example.com");
        invitations.issue(ownerActorId, "second@example.com");
        var expectedIds = jdbcClient.sql("""
                        SELECT id
                        FROM tenant_invitations
                        ORDER BY id
                        """)
                .query(UUID.class)
                .list();

        var page = invitations.list(
                ownerActorId,
                new InvitationQuery(null, null, InvitationSort.CREATED_AT_DESC, 0, 20)
        );

        assertEquals(expectedIds, page.items().stream().map(InvitationView::id).toList());
    }


    @Test
    void requiresAnActiveOwnerAndValidEmail() {
        ActorId unrelatedActor = new ActorId(UUID.randomUUID());
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)")
                .param("id", unrelatedActor.value())
                .update();

        var notOwner = assertThrows(
                InvitationException.class,
                () -> invitations.issue(unrelatedActor, "member@example.com")
        );
        var invalidEmail = assertThrows(
                InvitationException.class,
                () -> invitations.issue(ownerActorId, "not-an-email")
        );

        assertEquals(InvitationFailureReason.NOT_OWNER, notOwner.reason());
        assertEquals(InvitationFailureReason.INVALID_EMAIL, invalidEmail.reason());
        assertEquals(0L, count("tenant_invitations"));
    }

    @Test
    void rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets() {
        var original = invitations.issue(ownerActorId, "member@example.com");
        var duplicate = assertThrows(
                InvitationException.class,
                () -> invitations.issue(ownerActorId, "MEMBER@example.com")
        );
        assertEquals(InvitationFailureReason.CONFLICT, duplicate.reason());

        var rotated = invitations.rotate(ownerActorId, original.invitation().id());
        assertNotEquals(original.plaintextSecret(), rotated.plaintextSecret());
        assertEquals(
                InvitationFailureReason.NOT_AVAILABLE,
                assertThrows(
                        InvitationException.class,
                        () -> invitations.intake(original.plaintextSecret())
                ).reason()
        );
        assertEquals(rotated.invitation().id(), invitations.intake(rotated.plaintextSecret()).invitationId());
        assertEquals(InvitationDelivery.RECOVERY_LINK_ONLY, rotated.delivery());
        assertEquals(List.of("member@example.com"), provisionedEmails);

        invitations.revoke(ownerActorId, rotated.invitation().id());
        assertEquals(InvitationStatus.REVOKED, invitations.list(ownerActorId, InvitationQuery.defaults()).items().getFirst().status());
        assertEquals(
                InvitationFailureReason.NOT_AVAILABLE,
                assertThrows(
                        InvitationException.class,
                        () -> invitations.intake(rotated.plaintextSecret())
                ).reason()
        );
    }

    @Test
    void expiresPendingInvitationAndAllowsAReplacementForTheSameEmail() {
        var expired = invitations.issue(ownerActorId, "member@example.com");
        clock.advance(Duration.ofHours(73));

        assertEquals(InvitationStatus.EXPIRED, invitations.list(ownerActorId, InvitationQuery.defaults()).items().getFirst().status());
        assertEquals(
                InvitationFailureReason.NOT_AVAILABLE,
                assertThrows(
                        InvitationException.class,
                        () -> invitations.intake(expired.plaintextSecret())
                ).reason()
        );

        var replacement = invitations.issue(ownerActorId, "member@example.com");
        assertNotEquals(expired.invitation().id(), replacement.invitation().id());
        assertEquals(2L, count("tenant_invitations"));
    }

    @Test
    void acceptsVerifiedMatchingIdentityAndCreatesFixedMembershipsAtomically() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        var identity = new ExternalIdentity(ISSUER, "member-subject");

        ActorId member = invitations.accept(new InvitationAcceptance(
                continuation.invitationId(),
                continuation.tenantId(),
                identity,
                "Member@Example.com",
                true
        ));

        assertEquals(member.value(), jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'member-subject'
                        """)
                .param("issuer", ISSUER)
                .query(UUID.class)
                .single());
        assertEquals("MEMBER", tenantMembershipRole(member));
        var accepted = invitations.list(ownerActorId, InvitationQuery.defaults()).items().getFirst();
        assertEquals(InvitationStatus.ACCEPTED, accepted.status());
        assertEquals(member, accepted.acceptedActorId());
        assertEquals(
                InvitationFailureReason.NOT_AVAILABLE,
                assertThrows(
                        InvitationException.class,
                        () -> invitations.accept(new InvitationAcceptance(
                                continuation.invitationId(),
                                continuation.tenantId(),
                                identity,
                                "member@example.com",
                                true
                        ))
                ).reason()
        );
    }

    @Test
    void acceptsProvisionedInvitationByVerifiedEmailWithoutContinuation() {
        invitations.issue(ownerActorId, "member@example.com");

        ActorId member = invitations.acceptVerifiedEmail(
                new VerifiedEmailInvitationAcceptance(
                        new ExternalIdentity(ISSUER, "provisioned-member"),
                        "Member@Example.com",
                        true
                )
        );

        assertEquals("MEMBER", tenantMembershipRole(member));
        assertEquals(
                InvitationStatus.ACCEPTED,
                invitations.list(ownerActorId, InvitationQuery.defaults()).items().getFirst().status()
        );
        assertEquals(
                InvitationFailureReason.NOT_AVAILABLE,
                assertThrows(
                        InvitationException.class,
                        () -> invitations.acceptVerifiedEmail(
                                new VerifiedEmailInvitationAcceptance(
                                        new ExternalIdentity(ISSUER, "replay"),
                                        "member@example.com",
                                        true
                                )
                        )
                ).reason()
        );
    }

    @Test
    void rejectsUnverifiedOrUninvitedActivationWithoutContinuation() {
        invitations.issue(ownerActorId, "member@example.com");

        InvitationException unverified = assertThrows(
                InvitationException.class,
                () -> invitations.acceptVerifiedEmail(
                        new VerifiedEmailInvitationAcceptance(
                                new ExternalIdentity(ISSUER, "unverified"),
                                "member@example.com",
                                false
                        )
                )
        );
        InvitationException noMatch = assertThrows(
                InvitationException.class,
                () -> invitations.acceptVerifiedEmail(
                        new VerifiedEmailInvitationAcceptance(
                                new ExternalIdentity(ISSUER, "no-match"),
                                "other@example.com",
                                true
                        )
                )
        );

        assertEquals(InvitationFailureReason.EMAIL_NOT_VERIFIED, unverified.reason());
        assertEquals(InvitationFailureReason.NOT_AVAILABLE, noMatch.reason());
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(InvitationStatus.PENDING, invitations.list(ownerActorId, InvitationQuery.defaults()).items().getFirst().status());
    }

    @Test
    void replaysBootstrapAfterInvitationAddsAMember() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        invitations.accept(new InvitationAcceptance(
                continuation.invitationId(),
                continuation.tenantId(),
                new ExternalIdentity(ISSUER, "replay-member"),
                "member@example.com",
                true
        ));

        var replay = bootstrapper.bootstrap(bootstrapRequest());

        assertFalse(replay.created());
        assertEquals(ownerActorId, replay.ownerActorId());
        assertEquals(2L, count("tenant_memberships"));
    }

    @Test
    void rejectsUnverifiedOrMismatchedEmailWithoutIdentityWrites() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        var identity = new ExternalIdentity(ISSUER, "member-subject");

        var unverified = assertThrows(
                InvitationException.class,
                () -> invitations.accept(new InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.tenantId(),
                        identity,
                        "member@example.com",
                        false
                ))
        );
        var mismatch = assertThrows(
                InvitationException.class,
                () -> invitations.accept(new InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.tenantId(),
                        identity,
                        "other@example.com",
                        true
                ))
        );

        assertEquals(InvitationFailureReason.EMAIL_NOT_VERIFIED, unverified.reason());
        assertEquals(InvitationFailureReason.EMAIL_MISMATCH, mismatch.reason());
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(InvitationStatus.PENDING, invitations.list(ownerActorId, InvitationQuery.defaults()).items().getFirst().status());
    }

    @Test
    void rejectsAnIdentityThatAlreadyHasTenantAuthority() {
        var issued = invitations.issue(ownerActorId, "owner@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());

        var conflict = assertThrows(
                InvitationException.class,
                () -> invitations.accept(new InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.tenantId(),
                        new ExternalIdentity(ISSUER, "initial-owner"),
                        "owner@example.com",
                        true
                ))
        );

        assertEquals(InvitationFailureReason.IDENTITY_CONFLICT, conflict.reason());
        assertEquals(1L, count("tenant_memberships"));
    }

    @Test
    void concurrentAcceptanceProducesOneMemberAndOneAcceptedInvitation() throws Exception {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        var acceptance = new InvitationAcceptance(
                continuation.invitationId(),
                continuation.tenantId(),
                new ExternalIdentity(ISSUER, "concurrent-member"),
                "member@example.com",
                true
        );
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> acceptAfterSignal(acceptance, ready, start));
            var second = executor.submit(() -> acceptAfterSignal(acceptance, ready, start));
            assertTrue(ready.await(5, SECONDS));
            start.countDown();

            int successes = 0;
            int unavailable = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    future.get(5, SECONDS);
                    successes++;
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof InvitationException invitationException
                            && invitationException.reason()
                            == InvitationFailureReason.NOT_AVAILABLE) {
                        unavailable++;
                    } else if (exception.getCause() instanceof CannotAcquireLockException) {
                        unavailable++;
                    } else {
                        throw exception;
                    }
                }
            }
            assertEquals(1, successes);
            assertEquals(1, unavailable);
        }

        assertEquals(2L, count("actors"));
        assertEquals(2L, count("external_identity_bindings"));
        assertEquals(2L, count("tenant_memberships"));
        assertEquals("ACCEPTED", scalar("status"));
    }

    private ActorId acceptAfterSignal(
            InvitationAcceptance acceptance,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, SECONDS));
        return invitations.accept(acceptance);
    }

    private String tenantMembershipRole(ActorId actorId) {
        return jdbcClient.sql("SELECT role FROM tenant_memberships WHERE actor_id = :actorId")
                .param("actorId", actorId.value())
                .query(String.class)
                .single();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private static InitialTenantBootstrapRequest bootstrapRequest() {
        return new InitialTenantBootstrapRequest(
                new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000024")),
                new ExternalIdentity(ISSUER, "initial-owner"),
                "tasco",
                "Tasco",
                "TEST-MEM-12"
        );
    }

    private String scalar(String column) {
        return jdbcClient.sql(
                        "SELECT " + column + " FROM tenant_invitations ORDER BY created_at DESC LIMIT 1"
                )
                .query(String.class)
                .single();
    }

    private boolean databaseContains(String value) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM tenant_invitations
                        WHERE secret_digest = :value OR normalized_email = :value
                        """)
                .param("value", value)
                .query(Long.class)
                .single() != 0;
    }

}
