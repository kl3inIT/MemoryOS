package io.memoryos.invitation.persistence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.IdentityPersistence;
import io.memoryos.invitation.InvitationPersistence;
import io.memoryos.invitation.OrganizationInvitationException;
import io.memoryos.invitation.OrganizationInvitationService;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapper;
import io.memoryos.organization.OrganizationMembershipProvisioner;
import io.memoryos.organization.OrganizationPersistence;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class JdbcOrganizationInvitationServiceTest {

    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");

    private JdbcClient jdbcClient;
    private Connection keepAlive;
    private MutableClock clock;
    private OrganizationInvitationService invitations;
    private ActorId ownerActorId;

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
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                new ClassPathResource("db/migration/V3__create_organization_invitations.sql")
        ).populate(keepAlive);

        jdbcClient = JdbcClient.create(dataSource);
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        var resolver = IdentityPersistence.resolver(jdbcClient);
        var registrar = transactionalProxy(
                IdentityPersistence.registrar(jdbcClient, resolver),
                ExternalIdentityRegistrar.class,
                transactionManager
        );
        InitialOrganizationBootstrapper bootstrapper = transactionalProxy(
                OrganizationPersistence.initialBootstrapper(jdbcClient, resolver, registrar),
                InitialOrganizationBootstrapper.class,
                transactionManager
        );
        ownerActorId = bootstrapper.bootstrap(new InitialOrganizationBootstrapRequest(
                new ExternalIdentity(ISSUER, "initial-owner"),
                "tasco",
                "Tasco",
                "default",
                "Tasco Default Workspace",
                "TEST-MEM-12"
        )).ownerActorId();
        var membershipProvisioner = transactionalProxy(
                OrganizationPersistence.membershipProvisioner(jdbcClient),
                OrganizationMembershipProvisioner.class,
                transactionManager
        );
        clock = new MutableClock(START);
        invitations = transactionalProxy(
                InvitationPersistence.invitationService(
                        jdbcClient,
                        resolver,
                        registrar,
                        membershipProvisioner,
                        clock,
                        Duration.ofHours(72),
                        new SecureRandom()
                ),
                OrganizationInvitationService.class,
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
        assertEquals(OrganizationInvitationService.Status.PENDING, issued.invitation().status());
        assertEquals(43, issued.plaintextSecret().length());
        assertEquals(START.plus(Duration.ofHours(72)), issued.invitation().expiresAt());
        assertEquals(issued.invitation(), invitations.list(ownerActorId).getFirst());
        assertNotEquals(issued.plaintextSecret(), scalar("secret_digest"));
        assertFalse(databaseContains(issued.plaintextSecret()));
        assertEquals("member@example.com", scalar("open_email_key"));
    }

    @Test
    void requiresAnActiveOwnerAndValidEmail() {
        ActorId unrelatedActor = new ActorId(UUID.randomUUID());
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)")
                .param("id", unrelatedActor.value())
                .update();

        var notOwner = assertThrows(
                OrganizationInvitationException.class,
                () -> invitations.issue(unrelatedActor, "member@example.com")
        );
        var invalidEmail = assertThrows(
                OrganizationInvitationException.class,
                () -> invitations.issue(ownerActorId, "not-an-email")
        );

        assertEquals(OrganizationInvitationException.Reason.NOT_OWNER, notOwner.reason());
        assertEquals(OrganizationInvitationException.Reason.INVALID_EMAIL, invalidEmail.reason());
        assertEquals(0L, count("organization_invitations"));
    }

    @Test
    void rejectsDuplicatePendingEmailAndRotatesOrRevokesWithoutRecoveringOldSecrets() {
        var original = invitations.issue(ownerActorId, "member@example.com");
        var duplicate = assertThrows(
                OrganizationInvitationException.class,
                () -> invitations.issue(ownerActorId, "MEMBER@example.com")
        );
        assertEquals(OrganizationInvitationException.Reason.INVITATION_CONFLICT, duplicate.reason());

        var rotated = invitations.rotate(ownerActorId, original.invitation().id());
        assertEquals(2, rotated.invitation().secretVersion());
        assertNotEquals(original.plaintextSecret(), rotated.plaintextSecret());
        assertEquals(
                OrganizationInvitationException.Reason.INVITATION_NOT_AVAILABLE,
                assertThrows(
                        OrganizationInvitationException.class,
                        () -> invitations.intake(original.plaintextSecret())
                ).reason()
        );
        assertEquals(rotated.invitation().id(), invitations.intake(rotated.plaintextSecret()).invitationId());

        invitations.revoke(ownerActorId, rotated.invitation().id());
        assertEquals(OrganizationInvitationService.Status.REVOKED, invitations.list(ownerActorId).getFirst().status());
        assertEquals(
                OrganizationInvitationException.Reason.INVITATION_NOT_AVAILABLE,
                assertThrows(
                        OrganizationInvitationException.class,
                        () -> invitations.intake(rotated.plaintextSecret())
                ).reason()
        );
    }

    @Test
    void expiresPendingInvitationAndAllowsAReplacementForTheSameEmail() {
        var expired = invitations.issue(ownerActorId, "member@example.com");
        clock.advance(Duration.ofHours(73));

        assertEquals(OrganizationInvitationService.Status.EXPIRED, invitations.list(ownerActorId).getFirst().status());
        assertEquals(
                OrganizationInvitationException.Reason.INVITATION_NOT_AVAILABLE,
                assertThrows(
                        OrganizationInvitationException.class,
                        () -> invitations.intake(expired.plaintextSecret())
                ).reason()
        );

        var replacement = invitations.issue(ownerActorId, "member@example.com");
        assertNotEquals(expired.invitation().id(), replacement.invitation().id());
        assertEquals(2L, count("organization_invitations"));
    }

    @Test
    void acceptsVerifiedMatchingIdentityAndCreatesFixedMembershipsAtomically() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        var identity = new ExternalIdentity(ISSUER, "member-subject");

        ActorId member = invitations.accept(new OrganizationInvitationService.InvitationAcceptance(
                continuation.invitationId(),
                continuation.organizationId(),
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
        assertEquals("MEMBER", membershipRole("organization_memberships", member));
        assertEquals("MEMBER", membershipRole("workspace_memberships", member));
        var accepted = invitations.list(ownerActorId).getFirst();
        assertEquals(OrganizationInvitationService.Status.ACCEPTED, accepted.status());
        assertEquals(member, accepted.acceptedActorId());
        assertEquals(
                OrganizationInvitationException.Reason.INVITATION_NOT_AVAILABLE,
                assertThrows(
                        OrganizationInvitationException.class,
                        () -> invitations.accept(new OrganizationInvitationService.InvitationAcceptance(
                                continuation.invitationId(),
                                continuation.organizationId(),
                                identity,
                                "member@example.com",
                                true
                        ))
                ).reason()
        );
    }

    @Test
    void rejectsUnverifiedOrMismatchedEmailWithoutIdentityWrites() {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        var identity = new ExternalIdentity(ISSUER, "member-subject");

        var unverified = assertThrows(
                OrganizationInvitationException.class,
                () -> invitations.accept(new OrganizationInvitationService.InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.organizationId(),
                        identity,
                        "member@example.com",
                        false
                ))
        );
        var mismatch = assertThrows(
                OrganizationInvitationException.class,
                () -> invitations.accept(new OrganizationInvitationService.InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.organizationId(),
                        identity,
                        "other@example.com",
                        true
                ))
        );

        assertEquals(OrganizationInvitationException.Reason.EMAIL_NOT_VERIFIED, unverified.reason());
        assertEquals(OrganizationInvitationException.Reason.EMAIL_MISMATCH, mismatch.reason());
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(OrganizationInvitationService.Status.PENDING, invitations.list(ownerActorId).getFirst().status());
    }

    @Test
    void rejectsAnIdentityThatAlreadyHasOrganizationAuthority() {
        var issued = invitations.issue(ownerActorId, "owner@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());

        var conflict = assertThrows(
                OrganizationInvitationException.class,
                () -> invitations.accept(new OrganizationInvitationService.InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.organizationId(),
                        new ExternalIdentity(ISSUER, "initial-owner"),
                        "owner@example.com",
                        true
                ))
        );

        assertEquals(OrganizationInvitationException.Reason.IDENTITY_CONFLICT, conflict.reason());
        assertEquals(1L, count("organization_memberships"));
        assertEquals(1L, count("workspace_memberships"));
    }

    @Test
    void concurrentAcceptanceProducesOneMemberAndOneAcceptedInvitation() throws Exception {
        var issued = invitations.issue(ownerActorId, "member@example.com");
        var continuation = invitations.intake(issued.plaintextSecret());
        var acceptance = new OrganizationInvitationService.InvitationAcceptance(
                continuation.invitationId(),
                continuation.organizationId(),
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
                    if (exception.getCause() instanceof OrganizationInvitationException invitationException
                            && invitationException.reason()
                            == OrganizationInvitationException.Reason.INVITATION_NOT_AVAILABLE) {
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
        assertEquals(2L, count("organization_memberships"));
        assertEquals(2L, count("workspace_memberships"));
        assertEquals("ACCEPTED", scalar("status"));
    }

    private ActorId acceptAfterSignal(
            OrganizationInvitationService.InvitationAcceptance acceptance,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, SECONDS));
        return invitations.accept(acceptance);
    }

    private String membershipRole(String table, ActorId actorId) {
        return jdbcClient.sql("SELECT role FROM " + table + " WHERE actor_id = :actorId")
                .param("actorId", actorId.value())
                .query(String.class)
                .single();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private String scalar(String column) {
        return jdbcClient.sql("SELECT " + column + " FROM organization_invitations ORDER BY created_at DESC")
                .query(String.class)
                .single();
    }

    private boolean databaseContains(String value) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM organization_invitations
                        WHERE secret_digest = :value OR normalized_email = :value
                        """)
                .param("value", value)
                .query(Long.class)
                .single() != 0;
    }

    private static <T> T transactionalProxy(
            T target,
            Class<T> contract,
            PlatformTransactionManager transactionManager
    ) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(target);
        proxyFactory.setInterfaces(contract);
        proxyFactory.addAdvice(interceptor);
        return contract.cast(proxyFactory.getProxy());
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
