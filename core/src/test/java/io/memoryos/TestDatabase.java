package io.memoryos;

import io.memoryos.audit.AuditRequestContext;
import io.memoryos.audit.AuditTrail;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.sql.SQLException;
import javax.sql.DataSource;


import org.flywaydb.core.Flyway;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared database fixtures for core tests: the pinned PostgreSQL container (started once per test JVM and
 * reaped by Testcontainers), the production migration grammar, and transactional proxies for services.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class TestDatabase {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382"
    ).asCompatibleSubstituteFor("postgres");

    private static final String TEMPLATE_DATABASE = "memoryos_template";
    private static final String CLONE_PREFIX = "memoryos_clone_";

    private static PostgreSQLContainer postgres;
    private static boolean templateMigrated;
    private static int clones;

    private TestDatabase() {
    }

    /**
     * Returns a new database cloned from a template that the production Flyway migrations built once per test
     * JVM, so fixtures do not replay every migration. The caller owns the returned pool and must close it after
     * the fixture, including failed setup; clones whose pools are closed are dropped on later calls.
     */
    public static HikariDataSource freshPostgres() throws SQLException {
        PostgreSQLContainer container = postgres();
        String database;
        synchronized (TestDatabase.class) {
            if (!templateMigrated) {
                createDatabase(container, TEMPLATE_DATABASE, null);
                try (var template = pool(container, TEMPLATE_DATABASE)) {
                    Flyway.configure().dataSource(template).locations("classpath:db/migration").load().migrate();
                }
                templateMigrated = true;
            }
            database = CLONE_PREFIX + ++clones;
            dropUnusedClones(container);
            createDatabase(container, database, TEMPLATE_DATABASE);
        }
        return pool(container, database);
    }

    /**
     * Resets the shared PostgreSQL container's public schema and applies production Flyway migrations up to
     * {@code targetVersion}, for tests that exercise a migration step itself.
     */
    public static HikariDataSource freshPostgres(String targetVersion) throws SQLException {
        if ("latest".equals(targetVersion)) return freshPostgres();
        PostgreSQLContainer container = postgres();
        var dataSource = pool(container, container.getDatabaseName());
        try {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA public CASCADE");
                statement.execute("CREATE SCHEMA public");
            }
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target(targetVersion).load().migrate();
            return dataSource;
        } catch (SQLException | RuntimeException | Error failure) {
            dataSource.close();
            throw failure;
        }
    }

    private static HikariDataSource pool(PostgreSQLContainer container, String database) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database);
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    private static void createDatabase(PostgreSQLContainer container, String database, @org.jspecify.annotations.Nullable String template)
            throws SQLException {
        try (var admin = java.sql.DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
             var statement = admin.createStatement()) {
            // Identifiers are fixed prefixes and counters; PostgreSQL cannot bind database names.
            statement.execute("CREATE DATABASE " + database + (template == null ? "" : " TEMPLATE " + template));
        }
    }

    /** Frees disk from earlier fixtures; a clone still used by an open pool is kept. */
    private static void dropUnusedClones(PostgreSQLContainer container) throws SQLException {
        try (var admin = java.sql.DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
             var statement = admin.createStatement()) {
            var unused = new java.util.ArrayList<String>();
            try (var rows = statement.executeQuery("""
                    SELECT datname FROM pg_database
                    WHERE datname LIKE 'memoryos\\_clone\\_%'
                      AND datname NOT IN (SELECT datname FROM pg_stat_activity WHERE datname IS NOT NULL)
                    """)) {
                while (rows.next()) unused.add(rows.getString(1));
            }
            for (String database : unused) {
                try {
                    statement.execute("DROP DATABASE IF EXISTS " + database);
                } catch (SQLException inUse) {
                    // A pool connected between the query and the drop; keep that clone.
                }
            }
        }
    }

    public static <T> T transactionalProxy(
            T target,
            Class<T> contract,
            PlatformTransactionManager transactionManager
    ) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(target);
        // A service without an interface is proxied by subclass, as Spring proxies it in the application.
        if (contract.isInterface()) proxyFactory.setInterfaces(contract);
        else proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return contract.cast(proxyFactory.getProxy());
    }

    public static JpaHarness jpa(DataSource dataSource) {
        return jpa(dataSource, true);
    }

    /**
     * @param validateSchema pass {@code false} for tests that intentionally build the harness on an
     *     older migration target: at that point newer capabilities' tables (e.g. chat_image_connection)
     *     do not exist yet, and Hibernate schema validation would reject entities the test never touches.
     */
    public static JpaHarness jpa(DataSource dataSource, boolean validateSchema) {
        var factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPackagesToScan("io.memoryos.iam", "io.memoryos.ai.persistence", "io.memoryos.chat.persistence", "io.memoryos.mcp.persistence");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setJpaPropertyMap(java.util.Map.of(
                "hibernate.hbm2ddl.auto", validateSchema ? "validate" : "none",
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.cache.use_second_level_cache", "false",
                "hibernate.cache.use_query_cache", "false"
        ));
        factoryBean.setPersistenceUnitName("memoryos-test");
        factoryBean.afterPropertiesSet();
        EntityManagerFactory factory = java.util.Objects.requireNonNull(
                factoryBean.getObject(),
                "test EntityManagerFactory was not created"
        );
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        return new JpaHarness(entityManager, new JpaTransactionManager(factory), factory);
    }

    public record JpaHarness(
            EntityManager entityManager,
            JpaTransactionManager transactionManager,
            EntityManagerFactory entityManagerFactory
    ) implements AutoCloseable {
        /** Real Spring Data queries; the test's application transaction owns the unit of work. */
        public <R> R repository(Class<R> contract) {
            return repository(contract, RepositoryFragments.empty());
        }

        public <R> R repository(Class<R> contract, RepositoryFragments fragments) {
            R target = new JpaRepositoryFactory(entityManager).getRepository(contract, fragments);
            var proxy = new ProxyFactory(target);
            proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
            return contract.cast(proxy.getProxy());
        }

        @Override
        public void close() {
            entityManagerFactory.close();
        }
    }

    private static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("memoryos")
                    .withUsername("memoryos")
                    .withPassword("memoryos");
            postgres.start();
        }
        return postgres;
    }

    /** The audit writer over the test database; it joins whatever transaction the service under test opened. */
    public static AuditTrail audit(org.springframework.jdbc.core.simple.JdbcClient jdbc,
                                                         PlatformTransactionManager transactions) {
        return new AuditTrail(new io.memoryos.audit.persistence.JdbcAuditEventRepository(jdbc), AuditRequestContext.TRACE_ONLY,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), transactions);
    }

    /** An audit writer that records nothing, for tests of behaviour other than the audit stream itself. */
    public static AuditTrail noAudit() {
        var audit = org.mockito.Mockito.mock(AuditTrail.class);
        org.mockito.Mockito.when(audit.person(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                new AuditTrail.Person(
                        call.<java.util.UUID>getArgument(0).toString(), null));
        return audit;
    }
}
