package io.memoryos.chat.persona.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.memoryos.TestDatabase;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V181 keeps a task prompt inside the instructions, within their 32000 characters, and drops the unused fields. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PersonaTaskPromptFoldMigrationTest {
    @Test
    void aTaskPromptJoinsTheInstructionsAfterABlankLineAndTheFiveColumnsAreGone() throws Exception {
        try (var database = TestDatabase.freshPostgres("179")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'fold','Fold','ACTIVE','TEST')")
                    .param("id", tenant).update();
            jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", owner).update();
            jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                    .param("tenant", tenant).param("actor", owner).update();
            UUID both = persona(jdbc, tenant, owner, "Bạn trả lời câu hỏi về nhân sự.\n", "  Luôn ghi rõ tháng của báo cáo.  ");
            UUID onlyTask = persona(jdbc, tenant, owner, "   ", "Trả lời bằng tiếng Việt.");
            UUID untouched = persona(jdbc, tenant, owner, "Giữ nguyên.", "   ");
            UUID full = persona(jdbc, tenant, owner, "a".repeat(31990), "Repeat the question first.");
            long before = revision(jdbc, untouched);

            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("181").load();
            flyway.migrate();
            assertEquals("181", flyway.info().current().getVersion().getVersion());

            assertEquals("Bạn trả lời câu hỏi về nhân sự.\n\nLuôn ghi rõ tháng của báo cáo.", instructions(jdbc, both));
            assertEquals("Trả lời bằng tiếng Việt.", instructions(jdbc, onlyTask));
            assertEquals("Giữ nguyên.", instructions(jdbc, untouched));
            assertEquals(before, revision(jdbc, untouched), "an assistant without a task prompt keeps its revision");
            assertEquals(1, revision(jdbc, both) - before, "an open editor sees the folded instructions as a change");
            String folded = instructions(jdbc, full);
            assertEquals(32000, folded.length());
            assertEquals("a".repeat(31990) + "\n\nRepeat t", folded);

            for (String column : new String[] {"task_prompt", "knowledge_cutoff", "context_token_limit", "output_token_limit",
                    "replace_base_system_prompt"})
                assertFalse(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM information_schema.columns
                                       WHERE table_name = 'persona' AND column_name = :column)
                        """).param("column", column).query(Boolean.class).single(), column);
            jdbc.sql("UPDATE persona SET title = :title, greeting = :greeting, reasoning_effort = 'HIGH' WHERE id = :id")
                    .param("title", "t".repeat(80)).param("greeting", "g".repeat(500)).param("id", both).update();
        }
    }

    private static UUID persona(JdbcClient jdbc, UUID tenant, UUID owner, String instructions, String taskPrompt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO persona(id,tenant_id,owner_actor_id,name,instructions,task_prompt,knowledge_cutoff,
                                    context_token_limit,output_token_limit,replace_base_system_prompt)
                VALUES(:id,:tenant,:owner,'Trợ lý',:instructions,:task,CURRENT_TIMESTAMP,8000,1000,TRUE)
                """).param("id", id).param("tenant", tenant).param("owner", owner)
                .param("instructions", instructions).param("task", taskPrompt).update();
        return id;
    }

    private static String instructions(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT instructions FROM persona WHERE id = :id").param("id", id).query(String.class).single();
    }

    private static long revision(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT revision FROM persona WHERE id = :id").param("id", id).query(Long.class).single();
    }
}
