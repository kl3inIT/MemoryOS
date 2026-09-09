package io.memoryos.worker;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// SQL creates an isolated database in the test-owned PostgreSQL container.
@SuppressWarnings("SqlNoDataSourceInspection")
final class WorkerPostgresDatabase {

    private static final DockerImageName IMAGE = DockerImageName.parse(
            "postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382"
    ).asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;

    private WorkerPostgresDatabase() {
    }

    static synchronized void configure(DynamicPropertyRegistry registry) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(IMAGE)
                    .withDatabaseName("postgres")
                    .withUsername("memoryos")
                    .withPassword("memoryos");
            postgres.start();
        }
        configure(registry, postgres);
    }

    static void configure(DynamicPropertyRegistry registry, PostgreSQLContainer container) {
        if (!container.isRunning()) {
            container.start();
        }
        String database = "worker_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create isolated worker test database", exception);
        }
        String url = "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/" + database;
        Flyway.configure().dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").load().migrate();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.sql.init.mode", () -> "never");
    }
}
