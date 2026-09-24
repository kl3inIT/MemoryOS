package io.memoryos.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.library.work.DefaultUserFileWorkService;
import io.memoryos.chat.interpreter.InterpreterProperties;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.library.work.persistence.JdbcUserFileWorkRepository;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadException;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.DefaultStoredObjectRegistry;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import io.memoryos.chat.files.ChatFileAttachments;
import io.memoryos.chat.files.persistence.JdbcChatFileAttachmentRepository;
import io.memoryos.chat.files.ChatLibraryArtifacts;
import io.memoryos.chat.files.persistence.JdbcChatArtifactRepository;
import io.memoryos.chat.image.persistence.JdbcImageArtifactRepository;

/**
 * A library copy is a server write (V127): staged outside any transaction, adopted by the transaction that records
 * the file, discarded on every other outcome, and released by the file work when the copy is deleted. Real
 * PostgreSQL and object-storage lifecycle; the provider is a double.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class LibraryCopyIntegrationTest {
    private static final byte[] CONTENT = "một,hai".getBytes(StandardCharsets.UTF_8);

    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private final Map<String, String> types = new ConcurrentHashMap<>();
    /** Runs while the provider writes a copy's bytes, between staging and the owning transaction. */
    private volatile Consumer<String> onCopyWrite = key -> {};
    private ObjectStorage storage;
    private ObjectUploadService uploads;
    private DefaultObjectWriteService writes;
    private InterpreterService interpreter;
    private LibraryService library;
    private UserFileWorkPort work;
    private JdbcUserFileRepository files;
    private TenantId tenant;
    private ActorId owner;
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
            onCopyWrite.accept(key);
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> {
            byte[] bytes = stored.get(call.<ObjectKey>getArgument(0).value());
            return bytes == null ? null : new ObjectMetadata(bytes.length,
                    types.get(call.<ObjectKey>getArgument(0).value()), sha256(bytes));
        });
        when(storage.open(any())).thenAnswer(call -> {
            String key = call.<ObjectKey>getArgument(0).value();
            byte[] bytes = stored.get(key);
            var metadata = new ObjectMetadata(bytes.length, types.get(key), sha256(bytes));
            return new ObjectContent() {
                @Override public ObjectMetadata metadata() { return metadata; }
                @Override public InputStream inputStream() { return new ByteArrayInputStream(bytes); }
                @Override public void close() {}
            };
        });
        doAnswer(call -> stored.remove(call.<ObjectKey>getArgument(0).value())).when(storage).delete(any());
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var objects = new JdbcStoredObjectRepository(jdbc);
        writes = new DefaultObjectWriteService(objects, new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), 16), jpa.transactionManager());
        files = new JdbcUserFileRepository(jdbc);
        var libraryRows = new JdbcLibraryRepository(jdbc);
        var quotas = new StorageQuotaService(tenants, new LibraryStorageProperties(0), libraryRows);
        var retention = new LibraryTrashProperties(Duration.ofDays(30));
        interpreter = new InterpreterService(new JdbcInterpreterRepository(jdbc), new InterpreterProperties(null, null),
                mock(IamAuthorization.class), tenants, writes, storage, quotas, retention, jpa.transactionManager(),
                TestDatabase.noAudit());
        library = new LibraryService(tenants, libraryRows, files, new ChatFileAttachments(new JdbcChatFileAttachmentRepository(jdbc)),
                new ChatLibraryArtifacts(new JdbcChatArtifactRepository(jdbc), new JdbcInterpreterRepository(jdbc),
                        new JdbcImageArtifactRepository(jdbc)), storage, writes,
                new UserFileProperties(104857600, 262144000), quotas, jpa.transactionManager(),
                mock(UserFileSearchService.class));
        uploads = mock(ObjectUploadService.class);
        work = TestDatabase.transactionalProxy(new DefaultUserFileWorkService(new JdbcUserFileWorkRepository(jdbc),
                mock(DocumentCommandPort.class), uploads, tenants, new DefaultStoredObjectRegistry(objects), writes,
                storage), UserFileWorkPort.class, jpa.transactionManager());
        seedConversation();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void aCopyIsAnAdoptedServerWriteThatTheFileWorkReleasesWhenTheCopyIsDeleted() {
        var artifact = interpreter.store(tenant, messageId, "bao-cao.csv", "text/csv", CONTENT);

        var copy = library.copy(owner, LibraryFile.Source.GENERATED, artifact);
        assertEquals(UserFile.Status.PROCESSING, copy.status());
        assertEquals(copy.id(), library.copy(owner, LibraryFile.Source.GENERATED, artifact).id());
        var object = copyObject(copy.id());
        assertEquals("ADOPTED", writeStatus(object));
        assertEquals("ACTIVE", objectState(object));
        assertEquals(0L, count("object_uploads"));
        assertEquals(1L, count("chat_user_file WHERE upload_id IS NULL AND copied_from_source='GENERATED'"));

        // Deleting the copy lets the file work release the object it adopted, without any browser upload.
        files.delete(tenant, copy.id(), false, Duration.ZERO);
        var released = claimDelete(copy.id());
        assertTrue(work.deleted(released));
        assertEquals(0L, count("stored_objects WHERE id='" + object + "'"));
        assertEquals(0L, count("object_writes WHERE stored_object_id='" + object + "'"));
        assertEquals(1L, count("chat_user_file WHERE id='" + copy.id() + "' AND status='DELETED' AND stored_object_id IS NULL"));
        assertEquals(1, stored.size(), "only the artifact's bytes remain");
        verifyNoInteractions(uploads);
        // The artifact can be copied again.
        assertFalse(copy.id().equals(library.copy(owner, LibraryFile.Source.GENERATED, artifact).id()));
    }

    @Test
    void publishedMinutesAreWrittenTheSameWayAndReturnedAgainWhileTheyLive() {
        var meeting = UUID.randomUUID();
        var bytes = "# Biên bản".getBytes(StandardCharsets.UTF_8);

        var file = library.publish(owner, LibraryFile.Source.MEETING, meeting, "bien-ban.md", "text/markdown", bytes);
        assertEquals(UserFile.Status.PROCESSING, file.status());
        assertEquals(file.id(), library.publish(owner, LibraryFile.Source.MEETING, meeting, "bien-ban.md",
                "text/markdown", bytes).id());
        assertEquals("ADOPTED", writeStatus(copyObject(file.id())));
        assertEquals(0L, count("object_uploads"));
    }

    @Test
    void aStorageFailureDiscardsTheStagedCopyAtOnce() {
        var artifact = interpreter.store(tenant, messageId, "bao-cao.csv", "text/csv", CONTENT);
        onCopyWrite = key -> {
            throw new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true, null);
        };

        var failure = assertThrows(ObjectUploadException.class,
                () -> library.copy(owner, LibraryFile.Source.GENERATED, artifact));
        assertEquals("OBJECT_UPLOAD_STORAGE_UNAVAILABLE", failure.code());
        assertEquals(0L, count("chat_user_file"));
        // No pending reservation waits for an expiry: the stage is discarded and the next sweep deletes it.
        assertEquals(1L, count("object_writes WHERE status='DISCARDED'"));
        assertEquals(1L, count("stored_objects WHERE state='DELETE_PENDING'"));
        assertEquals(0L, count("object_uploads"));
        assertEquals(1, writes.cleanup(10));
        assertEquals(1, stored.size(), "the sweep deleted the copy's bytes; only the artifact's remain");
    }

    @Test
    void aCopyThatLosesTheRaceOrItsArtifactIsDiscardedAtOnce() {
        var artifact = interpreter.store(tenant, messageId, "bao-cao.csv", "text/csv", CONTENT);
        // A second request copies the same artifact while the first one's bytes are being written.
        var competitor = new UUID[1];
        onCopyWrite = key -> {
            onCopyWrite = ignored -> {};
            competitor[0] = library.copy(owner, LibraryFile.Source.GENERATED, artifact).id();
        };

        var copied = library.copy(owner, LibraryFile.Source.GENERATED, artifact).id();
        assertEquals(competitor[0], copied, "the loser returns the winner's copy");
        assertEquals(1L, count("chat_user_file"));
        assertEquals(2L, count("object_writes WHERE status='ADOPTED'"), "the artifact and the winner's copy");
        assertEquals(1L, count("object_writes WHERE status='DISCARDED'"));

        // An artifact deleted while its copy is written leaves nothing behind either.
        var other = interpreter.store(tenant, messageId, "khac.csv", "text/csv", CONTENT);
        onCopyWrite = key -> interpreter.delete(owner, other);
        assertThrows(LibraryException.class, () -> library.copy(owner, LibraryFile.Source.GENERATED, other));
        assertEquals(1L, count("chat_user_file"));
        assertEquals(2L, count("object_writes WHERE status='DISCARDED'"));
    }

    private UserFileWork claimDelete(UUID file) {
        var operation = jdbc.sql("""
                UPDATE chat_file_work SET delivery_id = id
                WHERE file_id = :file AND action = 'DELETE' AND status = 'NOT_STARTED' RETURNING id
                """).param("file", file).query(UUID.class).single();
        return work.claim(tenant, operation, operation).orElseThrow();
    }

    private UUID copyObject(UUID file) {
        return jdbc.sql("SELECT stored_object_id FROM chat_user_file WHERE id=:id").param("id", file)
                .query(UUID.class).single();
    }

    private String writeStatus(UUID object) {
        return jdbc.sql("SELECT status FROM object_writes WHERE stored_object_id=:id").param("id", object)
                .query(String.class).single();
    }

    private String objectState(UUID object) {
        return jdbc.sql("SELECT state FROM stored_objects WHERE id=:id").param("id", object).query(String.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static ContentSha256 sha256(byte[] bytes) throws Exception {
        return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    /** A conversation with one answer: a generated file takes its owner and session from it. */
    private void seedConversation() {
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Copy','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        var persona = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO persona(id,tenant_id,name,instructions,model,builtin_key)
                VALUES(:id,:tenant,'Default','','gpt','default')
                """).param("id", persona).param("tenant", tenant.value()).update();
        var session = UUID.randomUUID();
        var root = UUID.randomUUID();
        messageId = UUID.randomUUID();
        new TransactionTemplate(jpa.transactionManager()).executeWithoutResult(ignored -> {
            jdbc.sql("""
                    INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title)
                    VALUES(:id,:tenant,:actor,:persona,:root,'Copy')
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
