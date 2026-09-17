package io.memoryos.connector.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SourceAccessModesMigrationTest {
    @Test
    void renamesRestrictedToPrivateAndAcceptsOnlyTheThreeModes() throws Exception {
        try (var database = TestDatabase.freshPostgres("62")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = UUID.randomUUID(), credential = UUID.randomUUID(), restricted = UUID.randomUUID(), open = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'modes','Modes','ACTIVE','TEST')")
                    .param("id", tenant).update();
            jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test','NO_AUTH','ACTIVE')")
                    .param("id", credential).param("tenant", tenant).update();
            pair(jdbc, tenant, credential, restricted, "RESTRICTED");
            pair(jdbc, tenant, credential, open, "PUBLIC");

            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("77").load();
            assertEquals(15, flyway.migrate().migrationsExecuted);

            assertEquals("PRIVATE", access(jdbc, restricted));
            assertEquals("PUBLIC", access(jdbc, open));
            jdbc.sql("UPDATE connector_credential_pairs SET access_type='SYNC' WHERE id=:id").param("id", restricted).update();
            assertEquals("SYNC", access(jdbc, restricted));
            var rejected = assertThrows(DataIntegrityViolationException.class, () -> jdbc
                    .sql("UPDATE connector_credential_pairs SET access_type='RESTRICTED' WHERE id=:id").param("id", open).update());
            assertTrue(rejected.getMessage().contains("ck_pairs_access"));
            assertEquals("YES", jdbc.sql("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_name='google_drive_selection_operations' AND column_name='access_type'
                    """).query(String.class).single(), "Pending pre-migration intents keep a null access");
        }
    }

    private static void pair(JdbcClient jdbc, UUID tenant, UUID credential, UUID id, String access) {
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'Test','FILE','ACTIVE')")
                .param("id", id).param("tenant", tenant).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                VALUES(:id,:tenant,:id,:credential,:access,'ACTIVE')
                """).param("id", id).param("tenant", tenant).param("credential", credential).param("access", access).update();
    }

    private static String access(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT access_type FROM connector_credential_pairs WHERE id=:id")
                .param("id", id).query(String.class).single();
    }
}
