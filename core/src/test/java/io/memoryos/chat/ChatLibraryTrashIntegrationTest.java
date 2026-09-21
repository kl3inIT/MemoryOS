package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.application.ChatArtifactCleanupService;
import io.memoryos.chat.application.ChatRetentionProperties;
import io.memoryos.chat.interpreter.InterpreterProperties;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.chat.interpreter.JdbcInterpreterRepository;
import io.memoryos.chat.persistence.JdbcChatArtifactCleanupRepository;
import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.DefaultStoredObjectRegistry;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deleting a library file moves it to the trash: it leaves every listing at once, and its bytes are released
 * only when the window ends or the owner ends it (ADR 0014). Real PostgreSQL; storage IO is a double.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatLibraryTrashIntegrationTest {
    private static final Duration WINDOW = Duration.ofDays(30);

    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private final Map<String, String> types = new ConcurrentHashMap<>();
    private ObjectStorage storage;
    private JdbcUserFileRepository files;
    private JdbcChatLibraryRepository library;
    private JdbcInterpreterRepository artifacts;
    private ChatLibraryTrashService trash;
    private InterpreterService interpreter;
    private ChatArtifactCleanupService cleanup;
    private TenantId tenant;
    private ActorId owner;
    private ActorId other;
    private UUID messageId;

    @BeforeEach
    void setup() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        jpa = TestDatabase.jpa(database);
        storage = mock(ObjectStorage.class);
        doAnswer(call -> {
            String key = call.<ObjectKey>getArgument(0).value();
            stored.put(key, call.getArgument(1));
            types.put(key, call.getArgument(2));
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> {
            byte[] bytes = stored.get(call.<ObjectKey>getArgument(0).value());
            return bytes == null ? null : new ObjectMetadata(bytes.length,
                    types.get(call.<ObjectKey>getArgument(0).value()), sha256(bytes));
        });
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var objects = new JdbcStoredObjectRepository(jdbc);
        var writes = new DefaultObjectWriteService(objects, new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), 16), jpa.transactionManager());
        files = new JdbcUserFileRepository(jdbc);
        library = new JdbcChatLibraryRepository(jdbc);
        artifacts = new JdbcInterpreterRepository(jdbc);
        var quotas = new io.memoryos.chat.ChatStorageQuotaService(tenants,
                new io.memoryos.chat.application.ChatStorageProperties(0), library);
        interpreter = new InterpreterService(artifacts, new InterpreterProperties(null, null),
                mock(IamAuthorization.class), tenants, writes, storage, quotas,
                new ChatRetentionProperties(false, WINDOW, java.time.Duration.ZERO,
                        java.time.Duration.ofHours(24)), jpa.transactionManager(),
                io.memoryos.TestDatabase.noAudit());
        cleanup = new ChatArtifactCleanupService(new JdbcChatArtifactCleanupRepository(jdbc),
                new DefaultStoredObjectRegistry(objects), writes, storage, jpa.transactionManager());
        trash = new ChatLibraryTrashService(tenants, new JdbcChatRepository(jdbc), files, artifacts,
                new JdbcImageArtifactRepository(jdbc), new ChatRetentionProperties(false, WINDOW, java.time.Duration.ZERO,
                        java.time.Duration.ofHours(24)),
                jpa.transactionManager());
        seedConversation();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void aDeletedUploadWaitsInTheTrashUntilItsWindowEndsOrTheOwnerEndsIt() {
        var id = readyFile();
        files.delete(tenant, id, false, WINDOW);

        // Gone from the library, still nothing queued to release its bytes.
        assertEquals(List.of(), ids(page(false)));
        assertEquals(0, count("chat_file_work"));
        var trashed = page(true);
        assertEquals(List.of(id), ids(trashed));
        assertEquals(io.memoryos.chat.UserFile.Status.DELETING, trashed.items().getFirst().status());
        assertTrue(trashed.items().getFirst().deletedAt() != null);
        assertTrue(trashed.items().getFirst().purgeAfter() != null);

        // Restoring brings it back whole, because its document and text were never removed.
        trash.restore(owner, ChatLibraryFile.Source.UPLOAD, id);
        assertEquals(List.of(id), ids(page(false)));
        assertEquals(List.of(), ids(page(true)));
        assertThrows(ChatException.class, () -> trash.restore(owner, ChatLibraryFile.Source.UPLOAD, id));

        // Ending the window queues the release; the window itself does the same once it passes.
        files.delete(tenant, id, false, WINDOW);
        trash.purge(owner, ChatLibraryFile.Source.UPLOAD, id);
        assertEquals(1, count("chat_file_work"));
        assertFalse(files.restore(tenant, owner, id), "a file whose release is queued cannot come back");

        var second = readyFile();
        files.delete(tenant, second, false, WINDOW);
        assertEquals(0, files.enqueueDuePurges(10), "nothing is due yet");
        jdbc.sql("UPDATE chat_user_file SET purge_after = CURRENT_TIMESTAMP - INTERVAL '1' MINUTE WHERE id = :id")
                .param("id", second).update();
        assertEquals(1, files.enqueueDuePurges(10));
        assertEquals(0, files.enqueueDuePurges(10), "the queued release is not queued twice");
    }

    @Test
    void anotherMemberCannotRestoreOrPurgeWhatTheyDoNotOwn() {
        var id = readyFile();
        files.delete(tenant, id, false, WINDOW);

        assertThrows(ChatException.class, () -> trash.restore(other, ChatLibraryFile.Source.UPLOAD, id));
        assertThrows(ChatException.class, () -> trash.purge(other, ChatLibraryFile.Source.UPLOAD, id));
        assertEquals(List.of(), ids(library.page(tenant, other, new JdbcChatLibraryRepository.Filter("", Set.of(),
                Set.of(), null, false, false, true, null), ChatLibraryFile.Sort.DELETED, 0, 50)));
        assertEquals(0, count("chat_file_work"));
    }

    @Test
    void aDeletedArtifactKeepsItsBytesUntilTheWindowEndsAndComesBackWhenRestored() {
        var file = interpreter.store(tenant, messageId, "bao-cao.csv", "text/csv", "one".getBytes());
        interpreter.delete(owner, file);

        // Hidden, and the sweep leaves it alone while the window holds.
        assertEquals(List.of(), ids(page(false)));
        assertEquals(List.of(file), ids(page(true)));
        assertEquals(0, cleanup.cleanup());
        assertEquals(1, count("stored_objects"));

        trash.restore(owner, ChatLibraryFile.Source.GENERATED, file);
        assertEquals(List.of(file), ids(page(false)));
        assertEquals(0, cleanup.cleanup());

        // Emptying the trash ends every window the owner has, and the sweep then releases the bytes.
        interpreter.delete(owner, file);
        assertEquals(1, trash.empty(owner));
        assertEquals(1, cleanup.cleanup());
        assertEquals(0, count("stored_objects"));
        // The row survives as a tombstone so the answer keeps explaining itself, and the trash drops it:
        // there is nothing left to restore.
        assertEquals(1, count("chat_file_artifact WHERE purged_at IS NOT NULL"));
        assertEquals(List.of(), ids(page(true)));
        assertThrows(ChatException.class,
                () -> trash.restore(owner, ChatLibraryFile.Source.GENERATED, file));
    }

    private JdbcChatLibraryRepository.Page page(boolean trashed) {
        return library.page(tenant, owner, new JdbcChatLibraryRepository.Filter("", Set.of(), Set.of(), null,
                false, false, trashed, null), trashed ? ChatLibraryFile.Sort.DELETED : ChatLibraryFile.Sort.NEWEST, 0, 50);
    }

    private static List<UUID> ids(JdbcChatLibraryRepository.Page page) {
        return page.items().stream().map(ChatLibraryFile::id).toList();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static ContentSha256 sha256(byte[] bytes) throws Exception {
        return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    /** A READY upload with its extracted text, as the worker leaves one. */
    private UUID readyFile() {
        var objectId = new StoredObjectId(UUID.randomUUID());
        var uploadId = new ObjectUploadId(UUID.randomUUID());
        var spec = new ObjectUploadSpecification("Ghi chú.txt", "text/plain", 4,
                new ContentSha256("a".repeat(64)), ObjectUploadPurpose.CHAT_FILE);
        new JdbcStoredObjectRepository(jdbc).create(tenant, objectId,
                new ObjectKey("raw/" + tenant.value() + "/" + objectId.value()), spec,
                java.time.Instant.now().plusSeconds(600));
        new JdbcObjectUploadRepository(jdbc).create(tenant, uploadId, objectId, spec.purpose());
        var id = files.create(tenant, owner, UUID.randomUUID(), uploadId, spec);
        jdbc.sql("UPDATE chat_user_file SET status='READY', plaintext='Test' WHERE id=:id").param("id", id).update();
        return id;
    }

    /** A conversation with one answer: artifacts take their owner and session from it. */
    private void seedConversation() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(jpa.transactionManager());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Trash','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = new ActorId(UUID.randomUUID());
        other = new ActorId(UUID.randomUUID());
        for (var actor : List.of(owner, other)) {
            jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
            jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                    .param("tenant", tenant.value()).param("actor", actor.value()).update();
        }
        var persona = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO persona(id,tenant_id,name,instructions,model,builtin_key)
                VALUES(:id,:tenant,'Default','','gpt','default')
                """).param("id", persona).param("tenant", tenant.value()).update();
        var session = UUID.randomUUID();
        var root = UUID.randomUUID();
        messageId = UUID.randomUUID();
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("""
                    INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title)
                    VALUES(:id,:tenant,:actor,:persona,:root,'Trash')
                    """).param("id", session).param("tenant", tenant.value()).param("actor", owner.value())
                    .param("persona", persona).param("root", root).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,role,status,finished_at)
                    VALUES(:id,:session,'ROOT','COMPLETED',CURRENT_TIMESTAMP)
                    """).param("id", root).param("session", session).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,parent_message_id,role,status,finished_at,deadline_at)
                    VALUES(:id,:session,:parent,'ASSISTANT','COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """).param("id", messageId).param("session", session).param("parent", root).update();
        });
    }
}
