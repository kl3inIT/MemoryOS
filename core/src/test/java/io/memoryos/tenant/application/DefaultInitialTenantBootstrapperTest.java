package io.memoryos.tenant.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.persistence.JdbcExternalIdentityRegistrar;
import io.memoryos.identity.persistence.JdbcExternalIdentityResolver;
import io.memoryos.tenant.InitialTenantBootstrapRequest;
import io.memoryos.tenant.InitialTenantBootstrapper;
import io.memoryos.tenant.TenantBootstrapConflictException;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.persistence.JdbcTenantAccessResolver;
import io.memoryos.tenant.persistence.JdbcTenantBootstrapRepository;

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
class DefaultInitialTenantBootstrapperTest {

    private JdbcClient jdbcClient;
    private Connection keepAlive;
    private InitialTenantBootstrapper bootstrapper;

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
                new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql"),
                new ClassPathResource("db/migration/V5__create_file_source_and_document_schema.sql"),
                new ClassPathResource("db/migration/V6__cut_over_organization_to_tenant.sql")
        ).populate(keepAlive);

        jdbcClient = JdbcClient.create(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var resolver = new JdbcExternalIdentityResolver(jdbcClient);
        var registrar = transactionalProxy(
                new JdbcExternalIdentityRegistrar(jdbcClient, resolver),
                ExternalIdentityRegistrar.class,
                transactionManager
        );
        bootstrapper = transactionalProxy(
                new DefaultInitialTenantBootstrapper(
                        new JdbcTenantBootstrapRepository(jdbcClient),
                        resolver,
                        registrar
                ),
                InitialTenantBootstrapper.class,
                transactionManager
        );
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keepAlive.close();
    }

    @Test
    void currentSchemaContainsNoWorkspaceArtifacts() {
        assertEquals(0L, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('workspaces', 'workspace_memberships')
                        """)
                .query(Long.class)
                .single());
        assertEquals(0L, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND column_name IN ('workspace_id', 'default_workspace_id')
                        """)
                .query(Long.class)
                .single());
    }

    @Test
    void createsTheExactInitialAggregateAndReplaysTheSameConfiguration() {
        var request = request();

        var created = bootstrapper.bootstrap(request);
        var existing = bootstrapper.bootstrap(request);

        assertTrue(created.created());
        assertFalse(existing.created());
        assertEquals(created.ownerActorId(), existing.ownerActorId());
        assertEquals(created.tenantId(), existing.tenantId());
        assertEquals(1L, count("actors"));
        assertEquals(1L, count("external_identity_bindings"));
        assertEquals(1L, count("tenants"));
        assertEquals(1L, count("tenant_memberships"));
        assertEquals("OWNER", scalar("SELECT role FROM tenant_memberships"));
        assertEquals(created.tenantId().value(), jdbcClient.sql("""
                        SELECT tenant_id FROM tenant_bootstrap_state WHERE id = 1
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
            assertEquals(firstResult.tenantId(), secondResult.tenantId());
            assertEquals(1L, count("tenants"));
        }
    }

    @Test
    void resolvesOnlyActiveTenantMemberships() {
        var initial = bootstrapper.bootstrap(request());
        var accessResolver = new JdbcTenantAccessResolver(jdbcClient);

        assertTrue(accessResolver.hasActiveTenant(initial.ownerActorId()));

        jdbcClient.sql("""
                        UPDATE tenant_memberships
                        SET status = 'INACTIVE'
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", initial.tenantId().value())
                .param("actorId", initial.ownerActorId().value())
                .update();
        assertFalse(accessResolver.hasActiveTenant(initial.ownerActorId()));

        jdbcClient.sql("""
                        UPDATE tenant_memberships
                        SET status = 'ACTIVE'
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", initial.tenantId().value())
                .param("actorId", initial.ownerActorId().value())
                .update();
        jdbcClient.sql("UPDATE tenants SET status = 'INACTIVE' WHERE id = :tenantId")
                .param("tenantId", initial.tenantId().value())
                .update();
        assertFalse(accessResolver.hasActiveTenant(initial.ownerActorId()));
    }

    @Test
    void rejectsConfigurationDriftWithoutChangingTheExistingAggregate() {
        bootstrapper.bootstrap(request());
        var changed = new InitialTenantBootstrapRequest(
                new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000024")),
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "Different name",
                "DEPLOY-2026-08-19"
        );

        assertThrows(TenantBootstrapConflictException.class, () -> bootstrapper.bootstrap(changed));

        assertEquals("Tasco", scalar("SELECT display_name FROM tenants"));
        assertEquals(1L, count("tenants"));
    }

    @Test
    void rejectsAConfiguredTenantIdentifierThatDiffersFromThePublishedTenant() {
        bootstrapper.bootstrap(request());
        var changed = new InitialTenantBootstrapRequest(
                new TenantId(UUID.fromString("20000000-0000-0000-0000-000000000024")),
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "Tasco",
                "DEPLOY-2026-08-19"
        );

        assertThrows(TenantBootstrapConflictException.class, () -> bootstrapper.bootstrap(changed));
        assertEquals("10000000-0000-0000-0000-000000000024", scalar("SELECT CAST(id AS VARCHAR) FROM tenants"));
    }

    @Test
    void databaseRejectsASecondTenant() {
        bootstrapper.bootstrap(request());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcClient.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:id, 'second', 'Second', 'ACTIVE', 'TEST-SECOND')
                        """)
                .param("id", UUID.randomUUID())
                .update());
        assertEquals(1L, count("tenants"));
    }

    @Test
    void rollsBackTheIdentityBindingWhenAggregateCreationFails() {
        var invalid = new InitialTenantBootstrapRequest(
                new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000024")),
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "x".repeat(201),
                "DEPLOY-2026-08-19"
        );

        assertThrows(DataIntegrityViolationException.class, () -> bootstrapper.bootstrap(invalid));

        assertEquals(0L, count("actors"));
        assertEquals(0L, count("external_identity_bindings"));
        assertEquals(0L, count("tenants"));
        assertEquals(0L, count("tenant_memberships"));
        assertEquals(0L, jdbcClient.sql("""
                        SELECT COUNT(*) FROM tenant_bootstrap_state
                        WHERE tenant_id IS NOT NULL
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

    private static InitialTenantBootstrapRequest request() {
        return new InitialTenantBootstrapRequest(
                new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000024")),
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "tasco-owner"),
                "tasco",
                "Tasco",
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