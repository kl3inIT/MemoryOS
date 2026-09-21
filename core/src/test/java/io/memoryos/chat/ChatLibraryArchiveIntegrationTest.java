package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.application.ChatLibraryArchiveService;
import io.memoryos.chat.interpreter.InterpreterProperties;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.chat.interpreter.JdbcInterpreterRepository;
import io.memoryos.chat.persistence.JdbcChatLibraryArchiveRepository;
import io.memoryos.chat.persistence.JdbcChatLibraryArchiveRepository.Requested;
import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.DefaultStoredObjectRegistry;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A ZIP of a library selection is packed by the Worker, offered to its owner only, and released when it expires.
 * Real PostgreSQL and real adoption; storage IO is a controlled double that remembers what it was given.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatLibraryArchiveIntegrationTest {
    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private ObjectStorage storage;
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private final Map<String, String> types = new ConcurrentHashMap<>();
    private ChatLibraryArchiveService archives;
    private JdbcChatLibraryArchiveRepository repository;
    private InterpreterService interpreter;
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
            // A staged write is verified by reading it back, so the double answers with what it was given.
            types.put(key, call.getArgument(2));
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> metadata(call.getArgument(0)));
        when(storage.open(any())).thenAnswer(call -> {
            var key = call.<ObjectKey>getArgument(0);
            byte[] bytes = stored.get(key.value());
            if (bytes == null) throw new io.memoryos.objectstorage.ObjectStorageException(
                    io.memoryos.objectstorage.ObjectStorageFailureCode.NOT_FOUND, false, null);
            var described = metadata(key);
            return new ObjectContent() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                @Override public ObjectMetadata metadata() { return described; }
                @Override public java.io.InputStream inputStream() { return input; }
                @Override public void close() {}
            };
        });
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var objects = new JdbcStoredObjectRepository(jdbc);
        var writes = new DefaultObjectWriteService(objects, new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), 16), jpa.transactionManager());
        repository = new JdbcChatLibraryArchiveRepository(jdbc);
        interpreter = new InterpreterService(new JdbcInterpreterRepository(jdbc), new InterpreterProperties(null, null),
                mock(IamAuthorization.class), tenants, writes, storage,
                new io.memoryos.chat.ChatStorageQuotaService(tenants, mock(IamAuthorization.class),
                        new io.memoryos.chat.persistence.JdbcChatStorageQuotaRepository(jdbc),
                        new JdbcChatLibraryRepository(jdbc)),
                new io.memoryos.chat.application.ChatRetentionProperties(false, java.time.Duration.ZERO),
                jpa.transactionManager());
        // The service is used directly: its @Transactional boundaries are Spring's, and each call here is one
        // statement group against real PostgreSQL, which auto-commits without them.
        archives = new ChatLibraryArchiveService(tenants, repository, new JdbcChatLibraryRepository(jdbc),
                new JdbcUserFileRepository(jdbc), writes, storage, new DefaultStoredObjectRegistry(objects),
                jpa.transactionManager());
        seedConversation();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void packsTheSelectionIntoOneZipWithUniqueNamesAndSkipsWhatIsGone() throws Exception {
        var first = interpreter.store(tenant, messageId, "bao-cao.csv", "text/csv", "one".getBytes());
        var second = interpreter.store(tenant, messageId, "bao-cao.csv", "text/csv", "two".getBytes());
        var removed = interpreter.store(tenant, messageId, "sap-xoa.csv", "text/csv", "three".getBytes());
        var requested = List.of(new Requested(ChatLibraryFile.Source.GENERATED, first),
                new Requested(ChatLibraryFile.Source.GENERATED, second),
                new Requested(ChatLibraryFile.Source.GENERATED, removed));

        var archive = archives.request(owner, requested);
        assertEquals(JdbcChatLibraryArchiveRepository.Status.PENDING, archive.status());
        assertEquals(3, archive.fileCount());
        // The selection keeps being edited while the archive waits: this file is gone by packing time.
        interpreter.delete(owner, removed);

        assertTrue(archives.buildNext());
        assertFalse(archives.buildNext(), "one request, packed once");
        var packed = archives.get(owner, archive.id());
        assertEquals(JdbcChatLibraryArchiveRepository.Status.READY, packed.status());
        assertEquals(List.of(removed.toString()), packed.skipped());

        var entries = unzip(archives.open(owner, archive.id()));
        assertEquals(List.of("bao-cao.csv", "bao-cao (2).csv"), List.copyOf(entries.keySet()));
        assertEquals("one", new String(entries.get("bao-cao.csv")));
        assertEquals("two", new String(entries.get("bao-cao (2).csv")));

        // Only its owner reads it, and only by its own id.
        assertThrows(ChatException.class, () -> archives.open(other, archive.id()));
        assertThrows(ChatException.class, () -> archives.get(other, archive.id()));
        assertThrows(ChatException.class, () -> archives.open(owner, UUID.randomUUID()));
    }

    @Test
    void releasesTheBytesOfAnExpiredArchiveAndRefusesItBeforehand() {
        var file = interpreter.store(tenant, messageId, "ghi-chu.csv", "text/csv", "x".getBytes());
        var archive = archives.request(owner, List.of(new Requested(ChatLibraryFile.Source.GENERATED, file)));
        assertTrue(archives.buildNext());
        var key = jdbc.sql("SELECT object_key FROM chat_library_archive WHERE id = :id")
                .param("id", archive.id()).query(String.class).single();
        assertEquals(2, count("stored_objects"), "the artifact and the archive");

        // Nothing has expired yet.
        assertEquals(0, archives.sweepExpired());
        assertEquals(1, archives.list(owner).size());

        jdbc.sql("UPDATE chat_library_archive SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' MINUTE").update();
        // An expired archive is no longer offered, even before the sweep runs.
        assertTrue(archives.list(owner).isEmpty());
        assertThrows(ChatException.class, () -> archives.open(owner, archive.id()));

        assertEquals(1, archives.sweepExpired());
        verify(storage).delete(new ObjectKey(key));
        assertEquals(0, count("chat_library_archive"));
        assertEquals(1, count("stored_objects"), "the artifact keeps its own bytes");
        assertEquals(0, archives.sweepExpired());
    }

    @Test
    void refusesAnEmptyOversizedOrForeignSelectionAndTooManyAtOnce() {
        var file = interpreter.store(tenant, messageId, "ghi-chu.csv", "text/csv", "x".getBytes());
        var mine = new Requested(ChatLibraryFile.Source.GENERATED, file);
        assertThrows(ChatException.class, () -> archives.request(owner, List.of()));
        // Another member's files are not in the caller's library, so the request names nothing it may pack.
        assertThrows(ChatException.class, () -> archives.request(other, List.of(mine)));
        assertThrows(ChatException.class,
                () -> archives.request(owner, List.of(mine, new Requested(ChatLibraryFile.Source.GENERATED, UUID.randomUUID()))));

        // The selection's bytes are bounded, because the archive is written as one object.
        jdbc.sql("UPDATE chat_file_artifact SET size_bytes = :size WHERE id = :id")
                .param("size", ChatLibraryArchiveService.MAX_TOTAL_BYTES + 1).param("id", file).update();
        assertThrows(ChatException.class, () -> archives.request(owner, List.of(mine)));
        jdbc.sql("UPDATE chat_file_artifact SET size_bytes = 1 WHERE id = :id").param("id", file).update();

        // One person may only queue a few at a time; a duplicated file counts once.
        for (int queued = 0; queued < ChatLibraryArchiveService.MAX_ACTIVE_PER_OWNER; queued++) {
            assertEquals(1, archives.request(owner, List.of(mine, mine)).fileCount());
        }
        assertThrows(ChatException.class, () -> archives.request(owner, List.of(mine)));
    }

    @Test
    void aRequestThatKeepsFailingStopsBeingRetried() {
        var file = interpreter.store(tenant, messageId, "ghi-chu.csv", "text/csv", "x".getBytes());
        var archive = archives.request(owner, List.of(new Requested(ChatLibraryFile.Source.GENERATED, file)));
        // Every selected file has lost its bytes, so there is nothing to pack.
        stored.clear();

        assertTrue(archives.buildNext());
        var failed = archives.get(owner, archive.id());
        assertEquals(JdbcChatLibraryArchiveRepository.Status.FAILED, failed.status());
        assertEquals("None of the selected files is available any more.", failed.failure());
        assertFalse(archives.buildNext(), "a failed request is not claimed again");
        assertTrue(archives.list(owner).isEmpty(), "a failed archive is not offered");
    }

    private ObjectMetadata metadata(ObjectKey key) throws NoSuchAlgorithmException {
        byte[] bytes = stored.get(key.value());
        return bytes == null ? null
                : new ObjectMetadata(bytes.length, types.getOrDefault(key.value(), "application/zip"), sha256(bytes));
    }

    private static ContentSha256 sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private static Map<String, byte[]> unzip(ChatLibraryArchiveService.Download download) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var content = download.content(); var zip = new ZipInputStream(content.inputStream())) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** A conversation with one answer: artifacts take their owner and session from it. */
    private void seedConversation() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(jpa.transactionManager());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Archives','ACTIVE','test')")
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
        // chat_session and its root message reference each other; the deferred constraint needs one transaction.
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("""
                    INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title)
                    VALUES(:id,:tenant,:actor,:persona,:root,'Archives')
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
