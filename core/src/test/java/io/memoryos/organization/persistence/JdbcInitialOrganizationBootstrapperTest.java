package io.memoryos.organization.persistence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.IdentityPersistence;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapper;
import io.memoryos.organization.OrganizationBootstrapConflictException;
import io.memoryos.organization.OrganizationPersistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class JdbcInitialOrganizationBootstrapperTest {

    private JdbcClient jdbcClient;
    private Connection keepAlive;
    private InitialOrganizationBootstrapper bootstrapper;

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
                new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql")
        ).populate(keepAlive);

        jdbcClient = JdbcClient.create(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var resolver = IdentityPersistence.resolver(jdbcClient);
        var registrar = transactionalProxy(
                IdentityPersistence.registrar(jdbcClient, resolver),
                ExternalIdentityRegistrar.class,
                transactionManager
        );
        bootstrapper = transactionalProxy(
                OrganizationPersistence.initialBootstrapper(jdbcClient, resolver, registrar),
                InitialOrganizationBootstrapper.class,
                transactionManager
        );
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keepAlive.close();
    }

    @Test
    void createsTheExactInitialAggregateAndReplaysTheSameConfiguration() {
        var request = request();

        var created = bootstrapper.bootstrap(request);
        var existing = bootstrapper.bootstrap(request);

        assertTrue(created.created());
        assertFalse(existing.created());
        assertEquals(created.ownerActorId(), existing.ownerActorId());
        assertEquals(created.organizationId(), existing.organizationId());
        assertEquals(created.defaultWorkspaceId(), existing.defaultWorkspaceId());
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(1L, count("organizations"));
        assertEquals(1L, count("workspaces"));
        assertEquals(1L, count("organization_memberships"));
        assertEquals(1L, count("workspace_memberships"));
        assertEquals("OWNER", scalar("SELECT role FROM organization_memberships"));
        assertEquals("ADMIN", scalar("SELECT role FROM workspace_memberships"));
        assertEquals(created.organizationId().value(), jdbcClient.sql("""
                        SELECT initial_organization_id FROM organization_bootstrap_state WHERE id = 1
                        """)
                .query(UUID.class)
                .single());
    }

    @Test
    void serializesConcurrentStartupAndCreatesOneAggregate() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, SECONDS));
                return bootstrapper.bootstrap(request());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, SECONDS));
                return bootstrapper.bootstrap(request());
            });
            assertTrue(ready.await(5, SECONDS));
            start.countDown();

            var firstResult = first.get(5, SECONDS);
            var secondResult = second.get(5, SECONDS);

            assertNotEquals(firstResult.created(), secondResult.created());
            assertEquals(firstResult.ownerActorId(), secondResult.ownerActorId());
            assertEquals(firstResult.organizationId(), secondResult.organizationId());
            assertEquals(firstResult.defaultWorkspaceId(), secondResult.defaultWorkspaceId());
            assertEquals(1L, count("organizations"));
            assertEquals(1L, count("workspaces"));
        }
    }

    @Test
    void resolvesOnlyActiveOrganizationMemberships() {
        var initial = bootstrapper.bootstrap(request());
        var accessResolver = OrganizationPersistence.accessResolver(jdbcClient);

        assertTrue(accessResolver.hasActiveOrganization(initial.ownerActorId()));

        jdbcClient.sql("""
                        UPDATE organization_memberships
                        SET status = 'INACTIVE'
                        WHERE organization_id = :organizationId AND actor_id = :actorId
                        """)
                .param("organizationId", initial.organizationId().value())
                .param("actorId", initial.ownerActorId().value())
                .update();
        assertFalse(accessResolver.hasActiveOrganization(initial.ownerActorId()));

        jdbcClient.sql("""
                        UPDATE organization_memberships
                        SET status = 'ACTIVE'
                        WHERE organization_id = :organizationId AND actor_id = :actorId
                        """)
                .param("organizationId", initial.organizationId().value())
                .param("actorId", initial.ownerActorId().value())
                .update();
        jdbcClient.sql("UPDATE organizations SET status = 'INACTIVE' WHERE id = :organizationId")
                .param("organizationId", initial.organizationId().value())
                .update();
        assertFalse(accessResolver.hasActiveOrganization(initial.ownerActorId()));
    }

    @Test
    void rejectsConfigurationDriftWithoutChangingTheExistingAggregate() {
        bootstrapper.bootstrap(request());
        var changed = new InitialOrganizationBootstrapRequest(
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "Different name",
                "default",
                "Tasco Default Workspace",
                "DEPLOY-2026-08-19"
        );

        assertThrows(OrganizationBootstrapConflictException.class, () -> bootstrapper.bootstrap(changed));

        assertEquals("Tasco", scalar("SELECT display_name FROM organizations"));
        assertEquals(1L, count("organizations"));
    }

    @Test
    void rollsBackTheIdentityBindingWhenAggregateCreationFails() {
        var invalid = new InitialOrganizationBootstrapRequest(
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "x".repeat(201),
                "default",
                "Tasco Default Workspace",
                "DEPLOY-2026-08-19"
        );

        assertThrows(DataIntegrityViolationException.class, () -> bootstrapper.bootstrap(invalid));

        assertEquals(0L, count("actors"));
        assertEquals(0L, count("external_identity_bindings"));
        assertEquals(0L, count("organizations"));
        assertEquals(0L, count("workspaces"));
        assertEquals(0L, count("organization_memberships"));
        assertEquals(0L, count("workspace_memberships"));
        assertEquals(0L, jdbcClient.sql("""
                        SELECT COUNT(*) FROM organization_bootstrap_state
                        WHERE initial_organization_id IS NOT NULL
                        """)
                .query(Long.class)
                .single());
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

    private static InitialOrganizationBootstrapRequest request() {
        return new InitialOrganizationBootstrapRequest(
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "Tasco",
                "default",
                "Tasco Default Workspace",
                "DEPLOY-2026-08-19"
        );
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private String scalar(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}