package io.memoryos.chat.preferences.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.TestDatabase;
import io.memoryos.chat.ChatGuardrails;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V137 (MEM-208) turns each stored built-in topic into a whole topic of the Tenant: the switch and the reply a Tenant
 * chose survive, a Tenant that never touched the topics gets them all off, and the ids are the ones
 * {@link ChatGuardrails#BUILT_IN} names, so the seed of a new Tenant and a migrated one are the same topics.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class GuardrailTopicsMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void storedTopicsBecomeWholeTopicsKeepingTheTenantsChoices() throws Exception {
        try (var database = TestDatabase.freshPostgres("136")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = tenant(jdbc, "chosen");
            settings(jdbc, tenant, """
                    [{"topic":"POLITICS","enabled":true,"message":"Không bàn chính trị."},
                     {"topic":"LEADERS","enabled":false,"message":null}]""");

            migrate(database);

            var topics = topics(jdbc, tenant);
            assertEquals(ChatGuardrails.BUILT_IN.size(), topics.size());
            for (int index = 0; index < topics.size(); index++) {
                var seed = ChatGuardrails.BUILT_IN.get(index);
                var migrated = topics.get(index);
                assertEquals(seed.id().toString(), migrated.get("id").asString());
                assertEquals(seed.name(), migrated.get("name").asString());
                assertEquals(seed.description(), migrated.get("description").asString());
                assertEquals(seed.examples(), JSON.convertValue(migrated.get("examples"), List.class));
            }
            assertEquals(true, topics.get(0).get("enabled").asBoolean());
            assertEquals("Không bàn chính trị.", topics.get(0).get("message").asString());
            assertEquals(false, topics.get(1).get("enabled").asBoolean());
            assertEquals(ChatGuardrails.BUILT_IN.get(1).message(), topics.get(1).get("message").asString());
            assertEquals(false, topics.get(2).get("enabled").asBoolean(), "a topic the Tenant never stored is off");

            // At most 30 topics, as Bedrock bounds denied topics.
            String tooMany = "[" + String.join(",", Collections.nCopies(31, "{}")) + "]";
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                    "UPDATE chat_settings SET guardrail_topics = CAST(:topics AS jsonb) WHERE tenant_id = :tenant")
                    .param("topics", tooMany).param("tenant", tenant).update());
        }
    }

    @Test
    void aTenantThatNeverTouchedTheTopicsGetsThemAllOff() throws Exception {
        try (var database = TestDatabase.freshPostgres("136")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = tenant(jdbc, "untouched");
            settings(jdbc, tenant, "[]");

            migrate(database);

            var topics = topics(jdbc, tenant);
            assertEquals(ChatGuardrails.BUILT_IN.stream().map(seed -> seed.id().toString()).toList(),
                    topics.stream().map(topic -> topic.get("id").asString()).toList());
            assertEquals(List.of(false, false, false), topics.stream().map(topic -> topic.get("enabled").asBoolean()).toList());
        }
    }

    private static UUID tenant(JdbcClient jdbc, String slug) {
        UUID tenant = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,:slug,'ACTIVE','TEST')")
                .param("id", tenant).param("slug", slug).update();
        return tenant;
    }

    private static void settings(JdbcClient jdbc, UUID tenant, String topics) {
        jdbc.sql("INSERT INTO chat_settings(tenant_id, guardrail_topics) VALUES(:tenant, CAST(:topics AS jsonb))")
                .param("tenant", tenant).param("topics", topics).update();
    }

    private static List<JsonNode> topics(JdbcClient jdbc, UUID tenant) throws Exception {
        String stored = jdbc.sql("SELECT guardrail_topics::text FROM chat_settings WHERE tenant_id = :tenant")
                .param("tenant", tenant).query(String.class).single();
        var nodes = new ArrayList<JsonNode>();
        JSON.readTree(stored).forEach(nodes::add);
        return nodes;
    }

    private static void migrate(DataSource database) {
        var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("137").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
    }
}
