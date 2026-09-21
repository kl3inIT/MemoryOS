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
import io.memoryos.chat.application.ChatExportService;
import io.memoryos.chat.application.ChatFileProperties;
import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.application.DefaultChatSessionService;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.interpreter.InterpreterProperties;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.chat.interpreter.JdbcInterpreterRepository;
import io.memoryos.chat.persistence.JdbcChatExportRepository;
import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcChatSearchRepository;
import io.memoryos.chat.persistence.JdbcImageArtifactRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.ActorLanguageService;
import io.memoryos.iam.identity.persistence.ActorRefreshImpl;
import io.memoryos.iam.identity.persistence.JpaActorRepository;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadService;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Taking one's own data out (MEM-153): the Worker packs every conversation as JSON and as a readable page,
 * together with the owner's files, and the export is released when it expires. Real PostgreSQL; storage IO is a
 * double that remembers what it was given.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatExportIntegrationTest {
    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private ObjectStorage storage;
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private final Map<String, String> types = new ConcurrentHashMap<>();
    private ChatExportService exports;
    private ChatSessionService sessions;
    private ChatTurnPersistence turns;
    private InterpreterService interpreter;
    private TenantId tenant;
    private ActorId owner;
    private ActorId other;

    @BeforeEach
    void setup() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        jpa = TestDatabase.jpa(database);
        storage = mock(ObjectStorage.class);
        doAnswer(call -> {
            stored.put(call.<ObjectKey>getArgument(0).value(), call.getArgument(1));
            types.put(call.<ObjectKey>getArgument(0).value(), call.getArgument(2));
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> metadata(call.getArgument(0)));
        when(storage.open(any())).thenAnswer(call -> {
            var key = call.<ObjectKey>getArgument(0);
            byte[] bytes = stored.get(key.value());
            if (bytes == null) throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, null);
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
        var authorization = mock(IamAuthorization.class);
        var chats = new JdbcChatRepository(jdbc);
        sessions = TestDatabase.transactionalProxy(new DefaultChatSessionService(tenants, authorization, chats,
                new PersonaProperties(), new JdbcChatSearchRepository(jdbc)), ChatSessionService.class,
                jpa.transactionManager());
        var files = new ChatFileService(tenants, chats, new JdbcUserFileRepository(jdbc),
                mock(ObjectUploadService.class), new ChatFileProperties(104857600, 262144000),
                jpa.transactionManager());
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(jpa.transactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(new ChatTurnPersistence(tenants, authorization, chats, new PersonaProperties(),
                files, new ActorLanguageService(jpa.repository(JpaActorRepository.class,
                        org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments
                                .just(new ActorRefreshImpl(jpa.entityManager()))), tenants),
                new JdbcImageArtifactRepository(jdbc)));
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        turns = (ChatTurnPersistence) factory.getProxy();
        var objects = new JdbcStoredObjectRepository(jdbc);
        var writes = new DefaultObjectWriteService(objects, new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), 16), jpa.transactionManager());
        interpreter = new InterpreterService(new JdbcInterpreterRepository(jdbc), new InterpreterProperties(null, null),
                authorization, tenants, writes, storage, jpa.transactionManager(),
                io.memoryos.TestDatabase.noAudit());
        // Constructed directly: the service owns its own transaction template, as the archive service does.
        exports = new ChatExportService(tenants, new JdbcChatExportRepository(jdbc), chats,
                new JdbcChatLibraryRepository(jdbc), new JdbcUserFileRepository(jdbc), writes, storage,
                new DefaultStoredObjectRegistry(objects), jpa.transactionManager());
        seedTenant();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void packsEveryConversationAsDataAndAsAPageWithTheOwnersFiles() throws Exception {
        var session = sessions.create(owner, "Doanh thu quý 3");
        var pair = reserve(session, session.rootMessageId(), "Doanh thu tăng bao nhiêu?");
        turns.finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Tăng 12% <b>so với</b> Q2");
        interpreter.store(tenant, pair.assistantMessageId(), "bao-cao.csv", "text/csv", "revenue".getBytes());
        var archived = sessions.create(owner, "Đã lưu trữ");
        sessions.archive(owner, archived.id(), true);
        // A temporary conversation promised to leave nothing behind, so an export does not take it either.
        var temporary = sessions.create(owner, "Tạm thời", true);

        var requested = exports.request(owner);
        assertEquals(JdbcChatExportRepository.Status.PENDING, requested.status());
        // One at a time: an export reads everything the person owns.
        assertEquals("CHAT_CONFLICT", assertThrows(ChatException.class, () -> exports.request(owner)).code());

        assertTrue(exports.buildNext());
        assertFalse(exports.buildNext(), "one request, packed once");
        var ready = exports.get(owner, requested.id());
        assertEquals(JdbcChatExportRepository.Status.READY, ready.status());
        assertEquals(2, ready.sessionCount(), "the sidebar's conversation and the archived one");
        assertEquals(1, ready.fileCount());

        var entries = unzip(requested.id());
        assertTrue(entries.containsKey("index.html"));
        assertTrue(entries.keySet().stream().anyMatch(name -> name.startsWith("conversations/doanh-thu-quy-3-")));
        assertTrue(entries.containsKey("files/bao-cao.csv"));
        assertEquals("revenue", new String(entries.get("files/bao-cao.csv")));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains(temporary.id().toString())),
                "a temporary conversation is in no export");

        var page = entries.entrySet().stream().filter(entry -> entry.getKey().endsWith(".html")
                        && entry.getKey().startsWith("conversations/"))
                .map(entry -> new String(entry.getValue())).findFirst().orElseThrow();
        assertTrue(page.contains("Doanh thu tăng bao nhiêu?"), "the question is in the page");
        assertTrue(page.contains("Tăng 12% &lt;b&gt;so với&lt;/b&gt; Q2"), "an answer's own markup is escaped");
        var data = entries.entrySet().stream().filter(entry -> entry.getKey().endsWith(".json"))
                .map(entry -> new String(entry.getValue())).findFirst().orElseThrow();
        assertTrue(data.contains("\"role\" : \"USER\""));

        // Only its owner reads an export, and only by its own id.
        assertThrows(ChatException.class, () -> exports.get(other, requested.id()));
        assertThrows(ChatException.class, () -> exports.open(other, requested.id()));
        assertThrows(ChatException.class, () -> exports.open(owner, UUID.randomUUID()));
    }

    @Test
    void releasesTheBytesOfAnExpiredExportAndStopsOfferingItBeforehand() {
        sessions.create(owner, "Một hội thoại");
        var requested = exports.request(owner);
        assertTrue(exports.buildNext());
        var key = jdbc.sql("SELECT object_key FROM chat_export WHERE id = :id")
                .param("id", requested.id()).query(String.class).single();
        assertEquals(1, exports.list(owner).size());
        assertEquals(0, exports.sweepExpired(), "nothing has expired yet");

        jdbc.sql("UPDATE chat_export SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'").update();
        assertTrue(exports.list(owner).isEmpty(), "an expired export is no longer offered");
        assertThrows(ChatException.class, () -> exports.open(owner, requested.id()));

        assertEquals(1, exports.sweepExpired());
        verify(storage).delete(new ObjectKey(key));
        assertEquals(0, count("chat_export"));
        assertEquals(0, exports.sweepExpired());
        // Asking again is allowed once the last one is gone.
        assertEquals(JdbcChatExportRepository.Status.PENDING, exports.request(owner).status());
    }

    private Map<String, byte[]> unzip(UUID id) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        var download = exports.open(owner, id);
        try (var content = download.content(); var zip = new ZipInputStream(content.inputStream())) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private ChatTurnPersistence.Reservation reserve(ChatSession session, UUID parent, String text) {
        return turns.reserve(owner, session.id(), new ChatCommand(ChatCommand.Operation.SEND, parent,
                UUID.randomUUID(), text, null), Duration.ofMinutes(2), 32000, null);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private ObjectMetadata metadata(ObjectKey key) throws NoSuchAlgorithmException {
        byte[] bytes = stored.get(key.value());
        return bytes == null ? null
                : new ObjectMetadata(bytes.length, types.getOrDefault(key.value(), "application/zip"), sha256(bytes));
    }

    private static ContentSha256 sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private void seedTenant() {
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference)
                VALUES(:id,:slug,'Exports','ACTIVE','test')
                """).param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        owner = new ActorId(UUID.randomUUID());
        other = new ActorId(UUID.randomUUID());
        for (var actor : List.of(owner, other)) {
            jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
            jdbc.sql("""
                    INSERT INTO tenant_memberships(tenant_id,actor_id,role,status)
                    VALUES(:tenant,:actor,'MEMBER','ACTIVE')
                    """).param("tenant", tenant.value()).param("actor", actor.value()).update();
        }
        jdbc.sql("""
                INSERT INTO persona(id,tenant_id,name,instructions,model,builtin_key)
                VALUES(:id,:tenant,'Default','','gpt','default')
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).update();
    }
}
