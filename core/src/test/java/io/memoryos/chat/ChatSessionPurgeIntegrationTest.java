package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.application.ChatRetentionProperties;
import io.memoryos.chat.application.ChatSessionPurgeService;
import io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** With the deployment switch on, a deleted conversation stops existing and its artifacts reach the sweep. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatSessionPurgeIntegrationTest {
    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private JdbcChatSessionPurgeRepository repository;
    private TenantId tenant;
    private ActorId owner;

    @BeforeEach
    void setup() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        jpa = TestDatabase.jpa(database);
        repository = new JdbcChatSessionPurgeRepository(jdbc);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Purge','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", owner.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void purgesOnlyDeletedConversationsAndLeavesTheOwnersUploadAlone() {
        var kept = conversation("Kept", false, false);
        var deleted = conversation("Deleted", true, false);
        var upload = upload();

        assertEquals(1, purge(true));

        assertEquals(0, count("chat_session WHERE id='" + deleted.session + "'"));
        assertEquals(0, count("chat_message WHERE session_id='" + deleted.session + "'"));
        assertEquals(0, count("chat_sharing WHERE session_id='" + deleted.session + "'"));
        assertEquals(0, count("chat_feedback WHERE session_id='" + deleted.session + "'"));
        // The artifacts survive as rows until the MEM-142 sweep releases their bytes, but they are marked.
        assertEquals(1, count("chat_file_artifact WHERE id='" + deleted.file + "' AND deleted_at IS NOT NULL"));
        assertEquals(1, count("chat_image_artifact WHERE id='" + deleted.image + "' AND deleted_at IS NOT NULL"));
        // The live conversation and its artifacts are untouched, and so is the owner's upload.
        assertEquals(1, count("chat_session WHERE id='" + kept.session + "'"));
        assertEquals(1, count("chat_file_artifact WHERE id='" + kept.file + "' AND deleted_at IS NULL"));
        assertEquals(1, count("chat_user_file WHERE id='" + upload + "'"));
        assertEquals(0, purge(true));
    }

    @Test
    void keepsEveryDeletedConversationWhileTheSwitchIsOff() {
        var deleted = conversation("Deleted", true, false);

        assertEquals(0, purge(false));

        assertEquals(1, count("chat_session WHERE id='" + deleted.session + "'"));
        assertEquals(1, count("chat_file_artifact WHERE id='" + deleted.file + "' AND deleted_at IS NULL"));
    }

    @Test
    void leavesADeletedConversationWhoseReplyIsStillRunning() {
        var running = conversation("Running", true, true);

        assertEquals(0, purge(true));
        assertEquals(1, count("chat_session WHERE id='" + running.session + "'"));

        // Once the reply has ended, the next run takes it.
        jdbc.sql("UPDATE chat_message SET status='CANCELED', finished_at=CURRENT_TIMESTAMP WHERE session_id=:session AND status='RUNNING'")
                .param("session", running.session).update();
        assertEquals(1, purge(true));
        assertEquals(0, count("chat_session WHERE id='" + running.session + "'"));
    }

    private int purge(boolean hardDelete) {
        return new ChatSessionPurgeService(repository, new ChatRetentionProperties(hardDelete, java.time.Duration.ofDays(30)), jpa.transactionManager()).purge();
    }

    private long count(String from) {
        return jdbc.sql("SELECT count(*) FROM " + from).query(Long.class).single();
    }

    private record Conversation(UUID session, UUID file, UUID image) {}

    /** A conversation with one answer, one generated file, one image, a share and feedback. */
    private Conversation conversation(String title, boolean deleted, boolean running) {
        var persona = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO persona(id,tenant_id,name,instructions,model,builtin_key)
                VALUES(:id,:tenant,:name,'','gpt',NULL)
                """).param("id", persona).param("tenant", tenant.value()).param("name", "P-" + persona).update();
        var session = UUID.randomUUID();
        var root = UUID.randomUUID();
        var user = UUID.randomUUID();
        var answer = UUID.randomUUID();
        var file = UUID.randomUUID();
        var image = UUID.randomUUID();
        new TransactionTemplate(jpa.transactionManager()).executeWithoutResult(ignored -> {
            jdbc.sql("""
                    INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title,deleted_at)
                    VALUES(:id,:tenant,:actor,:persona,:root,:title,:deleted)
                    """).param("id", session).param("tenant", tenant.value()).param("actor", owner.value())
                    .param("persona", persona).param("root", root).param("title", title)
                    .param("deleted", deleted ? java.sql.Timestamp.from(java.time.Instant.now()) : null).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,role,status,finished_at)
                    VALUES(:id,:session,'ROOT','COMPLETED',CURRENT_TIMESTAMP)
                    """).param("id", root).param("session", session).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,parent_message_id,role,status,content,client_request_id,
                                             original_assistant_message_id,finished_at)
                    VALUES(:id,:session,:parent,'USER','COMPLETED','Câu hỏi',:request,:answer,CURRENT_TIMESTAMP)
                    """).param("id", user).param("session", session).param("parent", root).param("request", UUID.randomUUID())
                    .param("answer", answer).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,parent_message_id,role,status,content,finished_at,deadline_at)
                    VALUES(:id,:session,:parent,'ASSISTANT',:status,'Trả lời',:finished,CURRENT_TIMESTAMP)
                    """).param("id", answer).param("session", session).param("parent", user)
                    .param("status", running ? "RUNNING" : "COMPLETED")
                    .param("finished", running ? null : java.sql.Timestamp.from(java.time.Instant.now())).update();
        });
        jdbc.sql("""
                INSERT INTO chat_file_artifact(id,tenant_id,message_id,stored_object_id,object_key,filename,media_type,
                                               size_bytes,owner_actor_id,session_id)
                VALUES(:id,:tenant,:message,:object,:key,'báo cáo.xlsx','text/csv',8,:actor,:session)
                """).param("id", file).param("tenant", tenant.value()).param("message", answer)
                .param("object", UUID.randomUUID()).param("key", "raw/" + file).param("actor", owner.value())
                .param("session", session).update();
        jdbc.sql("""
                INSERT INTO chat_image_artifact(id,tenant_id,message_id,stored_object_id,object_key,media_type,
                                                owner_actor_id,session_id,filename,size_bytes)
                VALUES(:id,:tenant,:message,:object,:key,'image/png',:actor,:session,'image-1.png',4)
                """).param("id", image).param("tenant", tenant.value()).param("message", answer)
                .param("object", UUID.randomUUID()).param("key", "raw/" + image).param("actor", owner.value())
                .param("session", session).update();
        jdbc.sql("""
                INSERT INTO chat_sharing(tenant_id,session_id,enabled,revision)
                VALUES(:tenant,:session,TRUE,1)
                """).param("tenant", tenant.value()).param("session", session).update();
        jdbc.sql("""
                INSERT INTO chat_feedback(id,tenant_id,actor_id,session_id,assistant_message_id,positive,comment)
                VALUES(:id,:tenant,:actor,:session,:message,TRUE,'Tốt')
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("session", session)
                .param("message", answer).param("actor", owner.value()).update();
        return new Conversation(session, file, image);
    }

    private UUID upload() {
        var objectId = UUID.randomUUID();
        var uploadId = UUID.randomUUID();
        var spec = new io.memoryos.objectstorage.ObjectUploadSpecification("ghi chú.txt", "text/plain", 4,
                new io.memoryos.objectstorage.ContentSha256("a".repeat(64)), io.memoryos.objectstorage.ObjectUploadPurpose.CHAT_FILE);
        return java.util.Objects.requireNonNull(new TransactionTemplate(jpa.transactionManager()).execute(ignored -> {
            new io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository(jdbc).create(tenant,
                    new io.memoryos.objectstorage.StoredObjectId(objectId),
                    new io.memoryos.objectstorage.ObjectKey("raw/" + objectId), spec, java.time.Instant.now().plusSeconds(600));
            new io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository(jdbc).create(tenant,
                    new io.memoryos.objectstorage.ObjectUploadId(uploadId),
                    new io.memoryos.objectstorage.StoredObjectId(objectId), spec.purpose());
            return new io.memoryos.chat.persistence.JdbcUserFileRepository(jdbc).create(tenant, owner, UUID.randomUUID(),
                    new io.memoryos.objectstorage.ObjectUploadId(uploadId), spec);
        }));
    }
}
