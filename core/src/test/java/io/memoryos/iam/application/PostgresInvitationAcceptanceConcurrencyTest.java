package io.memoryos.iam.application;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.GroupProvisioner;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.iam.InvitationAcceptance;
import io.memoryos.iam.InvitationException;
import io.memoryos.iam.InvitationFailureReason;
import io.memoryos.iam.InvitationService;
import io.memoryos.iam.InvitationTarget;
import io.memoryos.iam.KeycloakRecipientProvisioning;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipProvisioner;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresInvitationAcceptanceConcurrencyTest {

    @Test
    void concurrentAcceptanceSerializesOnTenantThenInvitationAndCreatesOneMember() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
        var jdbcClient = JdbcClient.create(dataSource);
        try (JpaHarness jpa = TestDatabase.jpa(dataSource)) {
            var tenants = new JpaTenantRepository(jpa.entityManager());
            var identities = new JpaExternalIdentityRegistry(jpa.entityManager());
            var locks = new IamLockRepository(jdbcClient);
            GroupProvisioner groups = new DefaultGroupProvisioner(
                    new GroupRepository(jpa.entityManager()),
                    new GroupMembershipRepository(jpa.entityManager()),
                    new GroupCapabilityGrantRepository(jpa.entityManager())
            );
            TenantId tenantId = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000024"));
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
            ActorId owner = bootstrapper.bootstrap(new InitialTenantBootstrapRequest(
                    tenantId,
                    new ExternalIdentity("https://keycloak.example/realms/memoryos", "owner"),
                    "tasco",
                    "Tasco",
                    "TEST-MEM-36-CONCURRENCY"
            )).ownerActorId();
            var authorization = new DefaultIamAuthorization(
                    new IamAuthorizationRepository(jdbcClient),
                    locks
            );
            TenantMembershipProvisioner normalProvisioner = new JpaTenantMembershipProvisioner(tenants);
            var firstGrantEntered = new CountDownLatch(1);
            var releaseFirstGrant = new CountDownLatch(1);
            TenantMembershipProvisioner blockingProvisioner = blockingProvisioner(
                    normalProvisioner,
                    firstGrantEntered,
                    releaseFirstGrant
            );
            MutableClock clock = new MutableClock(Instant.parse("2026-09-06T10:00:00Z"));
            InvitationService issuer = service(
                    jpa,
                    jdbcClient,
                    identities,
                    normalProvisioner,
                    groups,
                    authorization,
                    locks,
                    clock
            );
            InvitationService firstService = service(
                    jpa,
                    jdbcClient,
                    identities,
                    blockingProvisioner,
                    groups,
                    authorization,
                    locks,
                    clock
            );
            InvitationService secondService = service(
                    jpa,
                    jdbcClient,
                    identities,
                    normalProvisioner,
                    groups,
                    authorization,
                    locks,
                    clock
            );
            var issued = issuer.issue(owner, "member@example.com");
            var continuation = issuer.intake(issued.plaintextSecret());
            var acceptance = new InvitationAcceptance(
                    continuation.invitationId(),
                    continuation.tenantId(),
                    new ExternalIdentity("https://keycloak.example/realms/memoryos", "member"),
                    "member@example.com",
                    true
            );

            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> firstService.accept(acceptance));
                try {
                    assertTrue(firstGrantEntered.await(10, SECONDS));
                    var second = executor.submit(() -> secondService.accept(acceptance));
                    assertThrows(TimeoutException.class, () -> second.get(250, MILLISECONDS));

                    releaseFirstGrant.countDown();
                    ActorId member = first.get(10, SECONDS);
                    ExecutionException failure = assertThrows(
                            ExecutionException.class,
                            () -> second.get(10, SECONDS)
                    );
                    InvitationException rejected = assertInstanceOf(InvitationException.class, failure.getCause());
                    assertEquals(InvitationFailureReason.NOT_AVAILABLE, rejected.reason());
                    assertEquals(2L, count(jdbcClient, "actors"));
                    assertEquals(2L, count(jdbcClient, "tenant_memberships"));
                    assertEquals(1L, jdbcClient.sql("""
                                    SELECT COUNT(*) FROM tenant_invitations
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
    }

    private static InvitationService service(
            JpaHarness jpa,
            JdbcClient jdbcClient,
            JpaExternalIdentityRegistry identities,
            TenantMembershipProvisioner membershipProvisioner,
            GroupProvisioner groupProvisioner,
            DefaultIamAuthorization authorization,
            IamLockRepository locks,
            MutableClock clock
    ) {
        var target = new DefaultInvitationService(
                new JpaInvitationRepository(jpa.entityManager()),
                new InvitationQueryRepository(jdbcClient),
                identities,
                membershipProvisioner,
                groupProvisioner,
                (_, _) -> KeycloakRecipientProvisioning.EXISTING_VERIFIED,
                authorization,
                locks,
                new TransactionTemplate(jpa.transactionManager()),
                clock,
                Duration.ofHours(72)
        );
        return TestDatabase.transactionalProxy(target, InvitationService.class, jpa.transactionManager());
    }

    private static TenantMembershipProvisioner blockingProvisioner(
            TenantMembershipProvisioner delegate,
            CountDownLatch entered,
            CountDownLatch release
    ) {
        return new TenantMembershipProvisioner() {
            @Override
            public Optional<InvitationTarget> findActiveInvitationTarget(TenantId tenantId) {
                return delegate.findActiveInvitationTarget(tenantId);
            }

            @Override
            public boolean hasAnyMembership(ActorId actorId) {
                return delegate.hasAnyMembership(actorId);
            }

            @Override
            public void grantMember(TenantId tenantId, ActorId actorId) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while holding IAM lifecycle locks", exception);
                }
                delegate.grantMember(tenantId, actorId);
            }
        };
    }

    private static long count(JdbcClient jdbcClient, String table) {
        Objects.requireNonNull(table);
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
