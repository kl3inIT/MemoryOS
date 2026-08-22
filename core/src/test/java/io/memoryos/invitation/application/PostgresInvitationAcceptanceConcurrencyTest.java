package io.memoryos.invitation.application;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.persistence.JdbcExternalIdentityRegistrar;
import io.memoryos.identity.persistence.JdbcExternalIdentityResolver;
import io.memoryos.invitation.InvitationAcceptance;
import io.memoryos.invitation.InvitationFailureReason;
import io.memoryos.invitation.persistence.JdbcInvitationRepository;
import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationService;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapper;
import io.memoryos.organization.InvitationAuthority;
import io.memoryos.organization.InvitationTarget;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.OrganizationMembershipProvisioner;
import io.memoryos.organization.application.DefaultInitialOrganizationBootstrapper;
import io.memoryos.organization.persistence.JdbcOrganizationBootstrapRepository;
import io.memoryos.organization.persistence.JdbcOrganizationMembershipProvisioner;
import io.memoryos.organization.WorkspaceId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresInvitationAcceptanceConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    @Test
    void concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        try (var connection = dataSource.getConnection()) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                    new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                    new ClassPathResource("db/migration/V3__create_organization_invitations.sql")
            ).populate(connection);
        }

        var jdbcClient = JdbcClient.create(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var resolver = new JdbcExternalIdentityResolver(jdbcClient);
        var registrar = transactionalProxy(
                new JdbcExternalIdentityRegistrar(jdbcClient, resolver),
                ExternalIdentityRegistrar.class,
                transactionManager
        );
        var normalProvisioner = transactionalProxy(
                new JdbcOrganizationMembershipProvisioner(jdbcClient),
                OrganizationMembershipProvisioner.class,
                transactionManager
        );
        var bootstrapRepository = new JdbcOrganizationBootstrapRepository(jdbcClient);
        InitialOrganizationBootstrapper bootstrapper = transactionalProxy(
                new DefaultInitialOrganizationBootstrapper(bootstrapRepository, resolver, registrar),
                InitialOrganizationBootstrapper.class,
                transactionManager
        );
        ActorId owner = bootstrapper.bootstrap(new InitialOrganizationBootstrapRequest(
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "owner"),
                "tasco",
                "Tasco",
                "default",
                "Tasco Default Workspace",
                "TEST-MEM-12-POSTGRES"
        )).ownerActorId();

        var firstGrantEntered = new CountDownLatch(1);
        var releaseFirstGrant = new CountDownLatch(1);
        var blockingProvisioner = blockingProvisioner(
                normalProvisioner,
                firstGrantEntered,
                releaseFirstGrant
        );
        Clock clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC);
        var invitationRepository = new JdbcInvitationRepository(jdbcClient);
        var issuer = transactionalProxy(
                new DefaultInvitationService(
                        invitationRepository,
                        resolver,
                        registrar,
                        normalProvisioner,
                        clock,
                        Duration.ofHours(72)
                ),
                InvitationService.class,
                transactionManager
        );
        var firstService = transactionalProxy(
                new DefaultInvitationService(
                        invitationRepository,
                        resolver,
                        registrar,
                        blockingProvisioner,
                        clock,
                        Duration.ofHours(72)
                ),
                InvitationService.class,
                transactionManager
        );
        var secondService = transactionalProxy(
                new DefaultInvitationService(
                        invitationRepository,
                        resolver,
                        registrar,
                        normalProvisioner,
                        clock,
                        Duration.ofHours(72)
                ),
                InvitationService.class,
                transactionManager
        );

        var issued = issuer.issue(owner, "member@example.com");
        var continuation = issuer.intake(issued.plaintextSecret());
        var acceptance = new InvitationAcceptance(
                continuation.invitationId(),
                continuation.organizationId(),
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "member"),
                "member@example.com",
                true
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> firstService.accept(acceptance));
            try {
                assertTrue(firstGrantEntered.await(10, SECONDS));
                var second = executor.submit(() -> secondService.accept(acceptance));
                assertTrue(waitForInvitationRowLock(jdbcClient));
                assertFalse(second.isDone());

                releaseFirstGrant.countDown();
                ActorId member = first.get(10, SECONDS);
                try {
                    second.get(10, SECONDS);
                    throw new AssertionError("second acceptance unexpectedly succeeded");
                } catch (ExecutionException exception) {
                    var invitationException = assertInstanceOf(
                            InvitationException.class,
                            exception.getCause()
                    );
                    assertEquals(
                            InvitationFailureReason.INVITATION_NOT_AVAILABLE,
                            invitationException.reason()
                    );
                }

                assertEquals(2L, count(jdbcClient, "actors"));
                assertEquals(2L, count(jdbcClient, "external_identity_bindings"));
                assertEquals(2L, count(jdbcClient, "organization_memberships"));
                assertEquals(2L, count(jdbcClient, "workspace_memberships"));
                assertEquals(1L, jdbcClient.sql("""
                                SELECT COUNT(*) FROM organization_invitations
                                WHERE status = 'ACCEPTED' AND accepted_by_actor_id = :actorId
                                """)
                        .param("actorId", member.value())
                        .query(Long.class)
                        .single());
            } finally {
                releaseFirstGrant.countDown();
            }
        }
    }

    private static OrganizationMembershipProvisioner blockingProvisioner(
            OrganizationMembershipProvisioner delegate,
            CountDownLatch entered,
            CountDownLatch release
    ) {
        return new OrganizationMembershipProvisioner() {
            @Override
            public Optional<InvitationAuthority> findInvitationAuthority(ActorId actorId) {
                return delegate.findInvitationAuthority(actorId);
            }

            @Override
            public Optional<InvitationTarget> findActiveInvitationTarget(
                    OrganizationId organizationId,
                    WorkspaceId defaultWorkspaceId
            ) {
                return delegate.findActiveInvitationTarget(organizationId, defaultWorkspaceId);
            }

            @Override
            public boolean hasAnyMembership(ActorId actorId) {
                return delegate.hasAnyMembership(actorId);
            }

            @Override
            public void grantDefaultMember(
                    OrganizationId organizationId,
                    WorkspaceId defaultWorkspaceId,
                    ActorId actorId
            ) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while holding invitation lock", exception);
                }
                delegate.grantDefaultMember(organizationId, defaultWorkspaceId, actorId);
            }
        };
    }

    private static boolean waitForInvitationRowLock(JdbcClient jdbcClient) throws InterruptedException {
        var deadline = System.nanoTime() + SECONDS.toNanos(10);
        do {
            long blocked = jdbcClient.sql("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%organization_invitations%'
                    """).query(Long.class).single();
            if (blocked > 0) {
                return true;
            }
            LockSupport.parkNanos(MILLISECONDS.toNanos(25));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        } while (System.nanoTime() < deadline);
        return false;
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

    private static long count(JdbcClient jdbcClient, String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
