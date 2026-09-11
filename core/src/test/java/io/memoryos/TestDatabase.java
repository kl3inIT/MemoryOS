package io.memoryos;

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

    private static PostgreSQLContainer postgres;

    private TestDatabase() {
    }

    /**
     * Resets the shared PostgreSQL container's public schema and applies production Flyway migrations.
     * The caller owns the returned pool and must close it after the fixture, including failed setup.
     */
    public static HikariDataSource freshPostgres() throws SQLException {
        return freshPostgres("latest");
    }

    public static HikariDataSource freshPostgres(String targetVersion) throws SQLException {
        PostgreSQLContainer container = postgres();
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(container.getJdbcUrl());
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(1);
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
        proxyFactory.setInterfaces(contract);
        proxyFactory.addAdvice(interceptor);
        return contract.cast(proxyFactory.getProxy());
    }

    public static JpaHarness jpa(DataSource dataSource) {
        var factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPackagesToScan("io.memoryos.iam.persistence", "io.memoryos.chat.persistence");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setJpaPropertyMap(java.util.Map.of(
                "hibernate.hbm2ddl.auto", "validate",
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
            R target = new JpaRepositoryFactory(entityManager).getRepository(contract);
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
}
