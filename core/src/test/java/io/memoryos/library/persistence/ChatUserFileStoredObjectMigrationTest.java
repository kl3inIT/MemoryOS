package io.memoryos.library.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.TestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V127 records the stored object a Chat file is: every file past upload and not yet released takes it from its
 * adopted upload, and a row without an upload must be a copy.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatUserFileStoredObjectMigrationTest {
    private static final String SHA = "a".repeat(64);

    @Test
    void backfillsTheObjectOfEveryFilePastUploadAndRequiresAnUploadOrACopyOrigin() throws Exception {
        try (var database = TestDatabase.freshPostgres("126")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'files','Files','ACTIVE','TEST')")
                    .param("id", tenant).update();
            jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", owner).update();
            jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                    .param("tenant", tenant).param("actor", owner).update();

            UUID readyObject = UUID.randomUUID(), failedObject = UUID.randomUUID(), trashedObject = UUID.randomUUID();
            UUID pendingObject = UUID.randomUUID(), releasedObject = UUID.randomUUID();
            var ready = file(jdbc, tenant, owner, upload(jdbc, tenant, readyObject, "ADOPTED"), "READY");
            var failed = file(jdbc, tenant, owner, upload(jdbc, tenant, failedObject, "ADOPTED"), "FAILED");
            var trashed = file(jdbc, tenant, owner, upload(jdbc, tenant, trashedObject, "ADOPTED"), "DELETING");
            var uploading = file(jdbc, tenant, owner, upload(jdbc, tenant, pendingObject, "PENDING"), "UPLOADING");
            var released = file(jdbc, tenant, owner, upload(jdbc, tenant, releasedObject, "ADOPTED"), "DELETED");
            var expired = file(jdbc, tenant, owner, upload(jdbc, tenant, null, "EXPIRED"), "FAILED");

            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("127").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);

            assertEquals(readyObject, object(jdbc, ready));
            assertEquals(failedObject, object(jdbc, failed));
            assertEquals(trashedObject, object(jdbc, trashed));
            // Only finalization adopts an upload's object; a released file no longer holds one; an expired upload has none.
            assertNull(object(jdbc, uploading));
            assertNull(object(jdbc, released));
            assertNull(object(jdbc, expired));

            // A file with neither a browser upload nor the artifact it was copied from is refused.
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                    INSERT INTO chat_user_file(id,tenant_id,owner_actor_id,request_id,filename,media_type,size_bytes,content_sha256)
                    VALUES(:id,:tenant,:owner,:request,'orphan.txt','text/plain',4,:sha)
                    """).param("id", UUID.randomUUID()).param("tenant", tenant).param("owner", owner)
                    .param("request", UUID.randomUUID()).param("sha", SHA).update());
            // A server-written copy names its object and origin, and two files never share one object.
            UUID copied = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO chat_user_file(id,tenant_id,owner_actor_id,request_id,stored_object_id,filename,media_type,
                        size_bytes,content_sha256,copied_from_source,copied_from_id)
                    VALUES(:id,:tenant,:owner,:request,:object,'copy.txt','text/plain',4,:sha,'GENERATED',:artifact)
                    """).param("id", copied).param("tenant", tenant).param("owner", owner).param("request", UUID.randomUUID())
                    .param("object", UUID.randomUUID()).param("sha", SHA).param("artifact", UUID.randomUUID()).update();
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                            "UPDATE chat_user_file SET stored_object_id=:object WHERE id=:id")
                    .param("object", readyObject).param("id", copied).update());
        }
    }

    private static UUID upload(JdbcClient jdbc, UUID tenant, @Nullable UUID object, String status) {
        var id = UUID.randomUUID();
        if (object != null) jdbc.sql("""
                INSERT INTO stored_objects(id,tenant_id,object_key,filename,size_bytes,declared_media_type,content_sha256,state,expires_at)
                VALUES(:id,:tenant,:key,'ghi-chu.txt',4,'text/plain',:sha,:state,CURRENT_TIMESTAMP + INTERVAL '1' DAY)
                """).param("id", object).param("tenant", tenant).param("key", "raw/" + tenant + "/" + object)
                .param("sha", SHA).param("state", "ADOPTED".equals(status) ? "ACTIVE" : "STAGED").update();
        boolean verified = "ADOPTED".equals(status);
        jdbc.sql("""
                INSERT INTO object_uploads(id,tenant_id,stored_object_id,status,verification_token,verified_at,adoption_deadline)
                VALUES(:id,:tenant,:object,:status,:token,:at,:at)
                """).param("id", id).param("tenant", tenant).param("object", object).param("status", status)
                .param("token", verified ? UUID.randomUUID() : null)
                .param("at", verified ? Timestamp.from(Instant.now()) : null).update();
        return id;
    }

    private static UUID file(JdbcClient jdbc, UUID tenant, UUID owner, UUID upload, String status) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO chat_user_file(id,tenant_id,owner_actor_id,request_id,upload_id,filename,media_type,size_bytes,
                    content_sha256,status)
                VALUES(:id,:tenant,:owner,:request,:upload,'ghi-chu.txt','text/plain',4,:sha,:status)
                """).param("id", id).param("tenant", tenant).param("owner", owner).param("request", UUID.randomUUID())
                .param("upload", upload).param("sha", SHA).param("status", status).update();
        return id;
    }

    private static @Nullable UUID object(JdbcClient jdbc, UUID file) {
        return jdbc.sql("SELECT stored_object_id FROM chat_user_file WHERE id=:id").param("id", file)
                .query((row, ignored) -> Optional.ofNullable(row.getObject("stored_object_id", UUID.class))).single()
                .orElse(null);
    }
}
