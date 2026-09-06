package io.memoryos;

import java.sql.SQLException;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
     * The current production schema: every migration in order.
     */
    public static ResourceDatabasePopulator migrations() {
        return new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql"),
                new ClassPathResource("db/migration/V5__create_file_source_and_document_schema.sql"),
                new ClassPathResource("db/migration/V6__cut_over_organization_to_tenant.sql"),
                new ClassPathResource("db/migration/V7__create_scheduler_control_plane.sql"),
                new ClassPathResource("db/migration/V8__cut_over_operations_to_redis_streams.sql"),
                new ClassPathResource("db/migration/V9__cut_over_file_content_to_object_storage.sql"),
                new ClassPathResource("db/migration/V10__add_document_extraction_artifacts.sql"),
                new ClassPathResource("db/migration/V11__use_current_documents.sql")
        );
    }

    /**
     * Resets the shared PostgreSQL container's public schema and applies {@link #migrations()}.
     */
    public static DriverManagerDataSource freshPostgres() throws SQLException {
        PostgreSQLContainer container = postgres();
        var dataSource = new DriverManagerDataSource(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword()
        );
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
            migrations().populate(connection);
        }
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
