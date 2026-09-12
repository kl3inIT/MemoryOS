package io.memoryos.objectstorage.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.TestDatabase;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ObjectStorageAdmissionMigrationTest {
    @Test
    void upgradesV38BinaryAdmissionWithoutChangingExistingDataOrOtherInputBoundaries() throws Exception {
        try (var database = TestDatabase.freshPostgres("38")) {
            var jdbc = JdbcClient.create(database);
            var tenant = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'admission','Admission','ACTIVE','TEST')")
                    .param("id", tenant).update();
            var binary = insertObject(jdbc, tenant, "BINARY", 10485760L);
            var nativeSnapshot = insertObject(jdbc, tenant, "NATIVE_SNAPSHOT", 33554432L);
            var chat = insertObject(jdbc, tenant, "CHAT_FILE", 262144000L);
            seedSourceVersion(jdbc, tenant, binary);
            var objectsBefore = jdbc.sql("SELECT row_to_json(o)::text FROM stored_objects o ORDER BY id")
                    .query(String.class).list();
            var versionBefore = jdbc.sql("SELECT row_to_json(v)::text FROM connector_item_versions v")
                    .query(String.class).single();
            assertThrows(DataIntegrityViolationException.class, () -> setObjectSize(jdbc, binary, 10485761L));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET size_bytes=10485761").update());

            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("39").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();
            assertEquals(objectsBefore, jdbc.sql("SELECT row_to_json(o)::text FROM stored_objects o ORDER BY id")
                    .query(String.class).list());
            assertEquals(versionBefore, jdbc.sql("SELECT row_to_json(v)::text FROM connector_item_versions v")
                    .query(String.class).single());

            setObjectSize(jdbc, binary, 104857600L);
            jdbc.sql("UPDATE connector_item_versions SET size_bytes=104857600").update();
            assertEquals(104857600L, jdbc.sql("SELECT size_bytes FROM stored_objects WHERE id=:id")
                    .param("id", binary).query(Long.class).single());
            assertEquals(104857600L, jdbc.sql("SELECT size_bytes FROM connector_item_versions").query(Long.class).single());
            assertThrows(DataIntegrityViolationException.class, () -> setObjectSize(jdbc, binary, 104857601L));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET size_bytes=104857601").update());
            assertThrows(DataIntegrityViolationException.class, () -> setObjectSize(jdbc, nativeSnapshot, 33554433L));
            assertThrows(DataIntegrityViolationException.class, () -> setObjectSize(jdbc, chat, 262144001L));

            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET scope_revision=1").update());
            jdbc.sql("""
                    UPDATE connector_item_versions
                    SET provider_file_id='drive-file', scope_revision=1, credential_revision=1
                    """).update();
            assertEquals(104857600L, jdbc.sql("SELECT size_bytes FROM connector_item_versions").query(Long.class).single());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET scope_revision=0").update());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET credential_revision=0").update());
            jdbc.sql("UPDATE connector_item_versions SET input_format='GOOGLE_DOCS', size_bytes=33554432").update();
            assertEquals(33554432L, jdbc.sql("SELECT size_bytes FROM connector_item_versions").query(Long.class).single());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET size_bytes=33554433").update());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET provider_file_id=NULL").update());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("UPDATE connector_item_versions SET input_format='CHAT_FILE'").update());
        }
    }

    private static UUID insertObject(JdbcClient jdbc, UUID tenant, String inputKind, long sizeBytes) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO stored_objects(id,tenant_id,object_key,filename,declared_media_type,size_bytes,
                    content_sha256,input_kind,state,expires_at)
                VALUES(:id,:tenant,CAST(:id AS TEXT),'input.pdf','application/pdf',:size,
                    REPEAT('a',64),:kind,'ACTIVE',CURRENT_TIMESTAMP)
                """).param("id", id).param("tenant", tenant).param("size", sizeBytes).param("kind", inputKind).update();
        return id;
    }

    private static void setObjectSize(JdbcClient jdbc, UUID id, long sizeBytes) {
        jdbc.sql("UPDATE stored_objects SET size_bytes=:size WHERE id=:id")
                .param("size", sizeBytes).param("id", id).update();
    }

    private static void seedSourceVersion(JdbcClient jdbc, UUID tenant, UUID object) {
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'File','FILE','ACTIVE')")
                .param("id", object).param("tenant", tenant).update();
        jdbc.sql("""
                INSERT INTO connector_items(id,tenant_id,connector_id,content_sha256,status)
                VALUES(:id,:tenant,:id,REPEAT('a',64),'INDEXED')
                """).param("id", object).param("tenant", tenant).update();
        jdbc.sql("""
                INSERT INTO connector_item_versions(id,tenant_id,connector_id,connector_item_id,revision_number,
                    filename,content_sha256,size_bytes,stored_object_id)
                VALUES(:id,:tenant,:id,:id,1,'input.pdf',REPEAT('a',64),10485760,:id)
                """).param("id", object).param("tenant", tenant).update();
    }
}
