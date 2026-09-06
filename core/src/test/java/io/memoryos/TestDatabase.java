package io.memoryos;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.sql.SQLException;
import javax.sql.DataSource;


import org.flywaydb.core.Flyway;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared database fixtures for core tests: the pinned PostgreSQL container (started once per test JVM and
 * reaped by Testcontainers), the production migration grammar, and transactional proxies for services.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection", "resource"})
public final class TestDatabase {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;

    private TestDatabase() {
    }


    /**
     * Resets the shared PostgreSQL container's public schema and applies production Flyway migrations.
     */
    public static DriverManagerDataSource freshPostgres() throws SQLException {
        return freshPostgres("latest");
    }

    public static DriverManagerDataSource freshPostgres(String targetVersion) throws SQLException {
        PostgreSQLContainer container = postgres();
        var dataSource = new DriverManagerDataSource(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
        );
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(targetVersion).load().migrate();
        return dataSource;
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
        factoryBean.setPackagesToScan("io.memoryos.iam.persistence");
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
