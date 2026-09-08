package io.memoryos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SchedulerSchemaMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    @Test
    void flywayCreatesTheSchedulerControlPlaneSchema() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        var jdbcClient = JdbcClient.create(dataSource);
        assertEquals(1, jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name = 'scheduled_tasks'
                        """)
                .query(Integer.class)
                .single());
        assertEquals(12, jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'scheduled_tasks'
                        """)
                .query(Integer.class)
                .single());
        assertEquals(3, jdbcClient.sql("""
                        SELECT COUNT(*) FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename = 'scheduled_tasks'
                          AND indexname LIKE 'ix_scheduled_tasks_%'
                        """)
                .query(Integer.class)
                .single());
        assertEquals(9, jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'index_attempts'
                          AND column_name IN (
                              'delivery_id',
                              'dispatch_token',
                              'dispatch_lease_expires_at',
                              'redis_message_id',
                              'next_dispatch_at',
                              'dispatched_at',
                              'dispatch_attempts',
                              'processing_attempts',
                              'last_transport_error'
                          )
                        """)
                .query(Integer.class)
                .single());
    }
}
