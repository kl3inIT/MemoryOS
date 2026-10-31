package io.memoryos.chat.files.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V90 gives existing artifacts an owner, a conversation and, for an image, a name and a size. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatFileLibraryMigrationTest {
    @Test
    void backfillsOwnerConversationImageNameAndSizeForArtifactsWrittenBeforeTheLibrary() throws Exception {
        try (var database = TestDatabase.freshPostgres("89")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID(), persona = UUID.randomUUID();
            UUID session = UUID.randomUUID(), root = UUID.randomUUID(), answer = UUID.randomUUID();
            UUID file = UUID.randomUUID(), image = UUID.randomUUID(), imageObject = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'library','Library','ACTIVE','TEST')")
                    .param("id", tenant).update();
            jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", owner).update();
            jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                    .param("tenant", tenant).param("actor", owner).update();
            jdbc.sql("""
                    INSERT INTO persona(id,tenant_id,name,instructions,model,builtin_key)
                    VALUES(:id,:tenant,'Default','','gpt','default')
                    """).param("id", persona).param("tenant", tenant).update();
            try (var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    // chat_session and its root message reference each other through a deferred constraint.
                    statement.execute("INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title) VALUES('"
                            + session + "','" + tenant + "','" + owner + "','" + persona + "','" + root + "','Cũ')");
                    statement.execute("INSERT INTO chat_message(id,session_id,role,status,finished_at) VALUES('"
                            + root + "','" + session + "','ROOT','COMPLETED',CURRENT_TIMESTAMP)");
                    statement.execute("INSERT INTO chat_message(id,session_id,parent_message_id,role,status,finished_at,deadline_at) VALUES('"
                            + answer + "','" + session + "','" + root + "','ASSISTANT','COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                }
                connection.commit();
            }
            jdbc.sql("""
                    INSERT INTO stored_objects(id,tenant_id,object_key,filename,size_bytes,declared_media_type,content_sha256,state,expires_at)
                    VALUES(:id,:tenant,'raw/pic.png','pic.png',4096,'image/png',:sha,'ACTIVE',CURRENT_TIMESTAMP + INTERVAL '1' DAY)
                    """).param("id", imageObject).param("tenant", tenant).param("sha", "a".repeat(64)).update();
            jdbc.sql("""
                    INSERT INTO chat_file_artifact(id,tenant_id,message_id,stored_object_id,object_key,filename,media_type,size_bytes)
                    VALUES(:id,:tenant,:message,:object,'raw/report.xlsx','báo cáo.xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',2048)
                    """).param("id", file).param("tenant", tenant).param("message", answer).param("object", UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO chat_image_artifact(id,tenant_id,message_id,stored_object_id,object_key,media_type,created_at)
                    VALUES(:id,:tenant,:message,:object,'raw/pic.png','image/png',TIMESTAMPTZ '2026-03-04 05:06:07+00')
                    """).param("id", image).param("tenant", tenant).param("message", answer).param("object", imageObject).update();

            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("90").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);

            assertEquals(owner, one(jdbc, "SELECT owner_actor_id FROM chat_file_artifact WHERE id='" + file + "'"));
            assertEquals(session, one(jdbc, "SELECT session_id FROM chat_file_artifact WHERE id='" + file + "'"));
            assertEquals(owner, one(jdbc, "SELECT owner_actor_id FROM chat_image_artifact WHERE id='" + image + "'"));
            assertEquals(session, one(jdbc, "SELECT session_id FROM chat_image_artifact WHERE id='" + image + "'"));
            // The name comes from the creation time so an old image sorts and searches like any other file.
            String name = jdbc.sql("SELECT filename FROM chat_image_artifact WHERE id=:id").param("id", image)
                    .query(String.class).single();
            assertTrue(name.startsWith("image-20260304-050607-") && name.endsWith(".png"), name);
            assertEquals(4096L, jdbc.sql("SELECT size_bytes FROM chat_image_artifact WHERE id=:id").param("id", image)
                    .query(Long.class).single());
            assertEquals(0L, jdbc.sql("SELECT count(*) FROM chat_file_artifact WHERE deleted_at IS NOT NULL")
                    .query(Long.class).single());
        }
    }

    private static UUID one(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(UUID.class).single();
    }
}
