package io.memoryos.api;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class ApiPostgresDatabase {

    private static final DockerImageName IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
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
