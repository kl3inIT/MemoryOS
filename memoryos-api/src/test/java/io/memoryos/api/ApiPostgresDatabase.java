package io.memoryos.api;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// SQL creates an isolated database in the test-owned PostgreSQL container.
@SuppressWarnings("SqlNoDataSourceInspection")
public final class ApiPostgresDatabase {

    private static final DockerImageName IMAGE = DockerImageName.parse(
            "postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382"
    ).asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer postgres;

    private ApiPostgresDatabase() {
    }

    public static synchronized void configure(DynamicPropertyRegistry registry) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(IMAGE)
                    .withDatabaseName("postgres")
                    .withUsername("memoryos")
                    .withPassword("memoryos");
            postgres.start();
        }
        String database = "api_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create isolated API test database", exception);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/" + database;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
