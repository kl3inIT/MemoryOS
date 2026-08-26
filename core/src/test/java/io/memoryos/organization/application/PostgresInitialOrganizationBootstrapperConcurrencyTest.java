package io.memoryos.organization.application;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.identity.persistence.JdbcExternalIdentityRegistrar;
import io.memoryos.identity.persistence.JdbcExternalIdentityResolver;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapper;
import io.memoryos.organization.persistence.JdbcOrganizationBootstrapRepository;

import java.util.concurrent.CountDownLatch;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresInitialOrganizationBootstrapperConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    @Test
    void concurrentBootstrapSerializesOnTheSingletonRowAndPublishesOneAggregate() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        try (var connection = dataSource.getConnection()) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                    new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                    new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                    new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql")
            ).populate(connection);
        }

        var jdbcClient = JdbcClient.create(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var resolver = new JdbcExternalIdentityResolver(jdbcClient);
        var firstResolverEntered = new CountDownLatch(1);
        var releaseFirstResolver = new CountDownLatch(1);
        ExternalIdentityResolver blockingResolver = identity -> {
            var actorId = resolver.resolve(identity);
            if (actorId.isEmpty()) {
                firstResolverEntered.countDown();
                try {
                    assertTrue(releaseFirstResolver.await(10, SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while holding the singleton bootstrap lock", exception);
                }
            }
            return actorId;
        };
        var firstRegistrar = transactionalProxy(
                new JdbcExternalIdentityRegistrar(jdbcClient, blockingResolver),
                ExternalIdentityRegistrar.class,
                transactionManager
        );
        var secondRegistrar = transactionalProxy(
                new JdbcExternalIdentityRegistrar(jdbcClient, resolver),
                ExternalIdentityRegistrar.class,
                transactionManager
        );
        var bootstrapRepository = new JdbcOrganizationBootstrapRepository(jdbcClient);
        var firstBootstrapper = transactionalProxy(
                new DefaultInitialOrganizationBootstrapper(
                        bootstrapRepository,
                        blockingResolver,
                        firstRegistrar
                ),
                InitialOrganizationBootstrapper.class,
                transactionManager
        );
        var secondBootstrapper = transactionalProxy(
                new DefaultInitialOrganizationBootstrapper(
                        bootstrapRepository,
                        resolver,
                        secondRegistrar
                ),
                InitialOrganizationBootstrapper.class,
                transactionManager
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> firstBootstrapper.bootstrap(request()));
            try {
                assertTrue(firstResolverEntered.await(10, SECONDS));
                var second = executor.submit(() -> secondBootstrapper.bootstrap(request()));
                assertTrue(waitForSingletonRowLock(jdbcClient));
                assertFalse(second.isDone());

                releaseFirstResolver.countDown();
                var firstResult = first.get(10, SECONDS);
                var secondResult = second.get(10, SECONDS);

                assertTrue(firstResult.created());
                assertFalse(secondResult.created());
                assertEquals(firstResult.ownerActorId(), secondResult.ownerActorId());
                assertEquals(firstResult.organizationId(), secondResult.organizationId());
                assertEquals(1L, count(jdbcClient, "actors"));
                assertEquals(1L, count(jdbcClient, "external_identity_bindings"));
                assertEquals(1L, count(jdbcClient, "organizations"));
                assertEquals(1L, count(jdbcClient, "organization_memberships"));
                assertEquals(1L, count(jdbcClient, "organization_bootstrap_state"));
            } finally {
                releaseFirstResolver.countDown();
            }
        }
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

    private static boolean waitForSingletonRowLock(JdbcClient jdbcClient) throws InterruptedException {
        var deadline = System.nanoTime() + SECONDS.toNanos(10);
        do {
            var blockedTransactions = jdbcClient.sql("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%organization_bootstrap_state%'
                    """).query(Long.class).single();
            if (blockedTransactions > 0) {
                return true;
            }
            LockSupport.parkNanos(MILLISECONDS.toNanos(25));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static InitialOrganizationBootstrapRequest request() {
        return new InitialOrganizationBootstrapRequest(
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "Tasco",
                "DEPLOY-2026-08-19"
        );
    }

    private static long count(JdbcClient jdbcClient, String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
