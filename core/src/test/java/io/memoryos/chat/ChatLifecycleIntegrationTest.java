package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.library.UserFileProperties;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.session.DefaultChatSessionService;
import io.memoryos.chat.interpreter.InterpreterProperties;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.session.persistence.JdbcChatSearchRepository;
import io.memoryos.chat.image.persistence.JdbcImageArtifactRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.ActorLanguageService;
import io.memoryos.iam.identity.persistence.ActorRefreshImpl;
import io.memoryos.iam.identity.persistence.JpaActorRepository;
import io.memoryos.iam.TenantAccessResolver;
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
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import io.memoryos.library.LibraryStorageProperties;
import io.memoryos.library.StorageQuotaService;
import io.memoryos.chat.files.ChatFileAttachments;
import io.memoryos.chat.files.persistence.JdbcChatFileAttachmentRepository;
import io.memoryos.library.LibraryTrashProperties;
import io.memoryos.chat.files.persistence.JdbcChatArtifactRepository;
import io.memoryos.library.UserFileService;

/**
 * The conversation lifecycle (MEM-153): archiving takes a conversation off the sidebar without losing it, and
 * branching copies a path into a conversation of its own, bytes and all. Real PostgreSQL; storage IO is a double
 * that remembers what it was given.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatLifecycleIntegrationTest {
    private HikariDataSource database;
    private TestDatabase.JpaHarness jpa;
    private JdbcClient jdbc;
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private final Map<String, String> types = new ConcurrentHashMap<>();
    private ChatSessionService sessions;
    private ChatTurnPersistence turns;
    private ChatBranchService branches;
    private InterpreterService interpreter;
    private JdbcChatRepository repository;
    private TenantId tenant;
    private ActorId owner;
    private ActorId other;
    private io.memoryos.StatementCounter statements;

    @BeforeEach
    void setup() throws Exception {
        database = TestDatabase.freshPostgres();
        statements = new io.memoryos.StatementCounter(database);
        jdbc = JdbcClient.create(statements);
        jpa = TestDatabase.jpa(statements);
        var storage = mock(ObjectStorage.class);
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
        repository = new JdbcChatRepository(jdbc);
        sessions = TestDatabase.transactionalProxy(new DefaultChatSessionService(tenants, authorization, repository,
                new JdbcChatSearchRepository(jdbc)), ChatSessionService.class,
                jpa.transactionManager());
        var quotas = new StorageQuotaService(tenants,
                new LibraryStorageProperties(0), new JdbcLibraryRepository(jdbc));
        var files = new UserFileService(tenants, new JdbcUserFileRepository(jdbc), new ChatFileAttachments(new JdbcChatFileAttachmentRepository(jdbc)),
                mock(ObjectUploadService.class), new UserFileProperties(104857600, 262144000), quotas,
                new LibraryTrashProperties(java.time.Duration.ZERO), jpa.transactionManager());
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(jpa.transactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(new ChatTurnPersistence(tenants, authorization, repository,
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
                authorization, tenants, writes, storage, quotas, new LibraryTrashProperties(java.time.Duration.ZERO),
                jpa.transactionManager(), io.memoryos.TestDatabase.noAudit());
        // Constructed directly: the service owns its own transaction template, which is what the copy relies on.
        branches = new ChatBranchService(tenants, repository, new JdbcChatArtifactRepository(jdbc), storage, writes,
                jpa.transactionManager());
        seedTenant();
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
        if (database != null) database.close();
    }

    @Test
    void archivingTakesAConversationOffTheSidebarAndWritingInItBringsItBack() {
        var kept = sessions.create(owner, "Kept");
        var archived = sessions.create(owner, "Archived");

        var state = sessions.archive(owner, archived.id(), true);
        assertTrue(state.archived());
        assertEquals(List.of(kept.id()), ids(sessions.list(owner, false, 0, 30)));
        assertEquals(List.of(archived.id()), ids(sessions.list(owner, true, 0, 30)));
        // It is still the owner's conversation: it opens by its id and answers as before.
        assertEquals(archived.id(), sessions.get(owner, archived.id()).id());

        // Archiving twice asks for the state it is already in.
        assertEquals(state.archivedAt(), sessions.archive(owner, archived.id(), true).archivedAt());
        // Nobody else may archive or unarchive it.
        assertEquals("CHAT_UNAVAILABLE",
                assertThrows(ChatException.class, () -> sessions.archive(other, archived.id(), true)).code());

        // Asking it something takes it back out of the archive, because it is a conversation being had again.
        reserve(archived, archived.rootMessageId(), "Back to it");
        assertFalse(sessions.get(owner, archived.id()).archived());
        assertEquals(List.of(archived.id(), kept.id()), ids(sessions.list(owner, false, 0, 30)));

        // Archive all: the sidebar empties and the archive holds everything.
        assertEquals(2, sessions.archiveAll(owner));
        assertTrue(sessions.list(owner, false, 0, 30).isEmpty());
        assertEquals(2, sessions.list(owner, true, 0, 30).size());
        assertEquals(0, sessions.archiveAll(owner), "nothing is left to archive");
        assertEquals(0, sessions.archiveAll(other));
    }

    @Test
    void branchingCopiesThePathAndItsGeneratedFilesSoEitherConversationCanBeDeleted() {
        var origin = sessions.create(owner, "Quarterly revenue");
        var first = reserve(origin, origin.rootMessageId(), "First question");
        turns.finish(origin.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "First answer");
        var artifact = interpreter.store(tenant, first.assistantMessageId(), "bao-cao.csv", "text/csv",
                "revenue".getBytes());
        var second = reserve(origin, first.assistantMessageId(), "Second question");
        turns.finish(origin.id(), second.assistantMessageId(), ChatMessage.Status.COMPLETED, "Second answer");

        var branch = branches.branch(owner, origin.id(), first.assistantMessageId());

        // The branch holds the path up to the chosen answer, and nothing after it.
        assertEquals(origin.id(), branch.branchedFromSessionId());
        assertEquals(first.assistantMessageId(), branch.branchedFromMessageId());
        assertEquals("Branch of Quarterly revenue", branch.title());
        assertEquals(origin.personaId(), branch.personaId());
        var history = sessions.history(owner, branch.id(), null, 100);
        assertEquals(List.of("First question", "First answer"),
                history.stream().map(ChatMessage::content).toList());
        assertEquals(List.of(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT),
                history.stream().map(ChatMessage::role).toList());
        assertNotEquals(first.userMessageId(), history.getFirst().id(), "a message belongs to one conversation");
        // The origin is untouched: it still has both turns on its selected path.
        assertEquals(4, sessions.history(owner, origin.id(), null, 100).size());

        // The generated file was copied, bytes and all, so it belongs to the branch's own answer.
        var copies = jdbc.sql("""
                SELECT a.id FROM chat_file_artifact a WHERE a.session_id = :session
                """).param("session", branch.id()).query(UUID.class).list();
        assertEquals(1, copies.size());
        assertNotEquals(artifact, copies.getFirst());
        assertEquals(2, count("chat_file_artifact"));
        assertEquals(2, count("stored_objects"), "each conversation has its own bytes");
        assertEquals("revenue", new String(stored.get(key(copies.getFirst()))));

        // Deleting the origin leaves the branch whole, which is what its header link promises.
        for (var released : repository.delete(origin.id())) assertNotNull(released);
        assertEquals("revenue", new String(stored.get(key(copies.getFirst()))));
        assertEquals(2, sessions.history(owner, branch.id(), null, 100).size());

        // The branch carries on by itself.
        var next = reserve(branch, history.getLast().id(), "Only in the branch");
        turns.finish(branch.id(), next.assistantMessageId(), ChatMessage.Status.COMPLETED, "Branch answer");
        assertEquals(4, sessions.history(owner, branch.id(), null, 100).size());
    }

    @Test
    void branchingCopiesEveryMessageInOneStatementAndLooksUpArtifactsOnce() {
        var origin = sessions.create(owner, "Long conversation");
        UUID parent = origin.rootMessageId();
        for (int turn = 0; turn < 6; turn++) {
            var pair = reserve(origin, parent, "Question " + turn);
            turns.finish(origin.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer " + turn);
            if (turn % 2 == 0) interpreter.store(tenant, pair.assistantMessageId(), "turn-" + turn + ".csv", "text/csv",
                    ("turn " + turn).getBytes());
            parent = pair.assistantMessageId();
        }

        statements.reset();
        var branch = branches.branch(owner, origin.id(), parent);

        // Twelve messages and three generated files: one copy statement and one lookup per artifact table.
        assertEquals(1, statements.count("original_assistant_message_id, created_at"), statements.statements().toString());
        assertEquals(1, statements.count("FROM chat_file_artifact a"), statements.statements().toString());
        assertEquals(1, statements.count("FROM chat_image_artifact a"), statements.statements().toString());
        var history = sessions.history(owner, branch.id(), null, 100);
        assertEquals(12, history.size());
        assertEquals("Question 0", history.getFirst().content());
        assertEquals("Answer 5", history.getLast().content());
        assertEquals(List.of("Answer 0:turn-0.csv", "Answer 2:turn-2.csv", "Answer 4:turn-4.csv"), jdbc.sql("""
                SELECT m.content || ':' || a.filename FROM chat_file_artifact a JOIN chat_message m ON m.id = a.message_id
                WHERE a.session_id = :session ORDER BY a.filename
                """).param("session", branch.id()).query(String.class).list());
    }

    @Test
    void branchingAQuestionTakesItsAnswerAndRefusesWhatCannotBeCopied() {
        var origin = sessions.create(owner, "Answers");
        var pair = reserve(origin, origin.rootMessageId(), "Question");

        // An answer still being written cannot be copied: the branch would hold a turn nobody finished.
        assertEquals("CHAT_CONFLICT",
                assertThrows(ChatException.class, () -> branches.branch(owner, origin.id(), pair.userMessageId())).code());
        turns.finish(origin.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer");

        // Naming the question copies the answer that followed it, because a branch carries on from a whole turn.
        var branch = branches.branch(owner, origin.id(), pair.userMessageId());
        assertEquals(List.of("Question", "Answer"),
                sessions.history(owner, branch.id(), null, 100).stream().map(ChatMessage::content).toList());
        assertEquals(pair.userMessageId(), branch.branchedFromMessageId());
        assertNull(sessions.get(owner, branch.id()).archivedAt());

        // Nobody else may branch it, and a message of another conversation is not on this one's path.
        assertEquals("CHAT_UNAVAILABLE",
                assertThrows(ChatException.class, () -> branches.branch(other, origin.id(), pair.userMessageId())).code());
        assertEquals("CHAT_UNAVAILABLE",
                assertThrows(ChatException.class, () -> branches.branch(owner, origin.id(), UUID.randomUUID())).code());

        // A temporary conversation is not branched: the branch would outlive what promised to leave nothing.
        var temporary = sessions.create(owner, "Tạm thời", true);
        var temporaryPair = reserve(temporary, temporary.rootMessageId(), "Câu hỏi");
        turns.finish(temporary.id(), temporaryPair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Trả lời");
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class,
                () -> branches.branch(owner, temporary.id(), temporaryPair.assistantMessageId())).code());

        // A generated file whose bytes are gone stops the copy rather than producing a branch missing them.
        var second = reserve(origin, pair.assistantMessageId(), "Second question");
        turns.finish(origin.id(), second.assistantMessageId(), ChatMessage.Status.COMPLETED, "Second answer");
        interpreter.store(tenant, second.assistantMessageId(), "ghi-chu.csv", "text/csv", "x".getBytes());
        long objects = count("stored_objects");
        stored.clear();
        assertThrows(ChatException.class, () -> branches.branch(owner, origin.id(), second.assistantMessageId()));
        assertEquals(objects, count("stored_objects"), "a refused branch leaves nothing behind");
        assertEquals(2, sessions.list(owner, false, 0, 30).size(), "and no conversation");
    }

    private static void assertNotNull(Object value) {
        assertTrue(value != null);
    }

    private ChatTurnPersistence.Reservation reserve(ChatSession session, UUID parent, String text) {
        return turns.reserve(owner, session.id(), new ChatCommand(ChatCommand.Operation.SEND, parent,
                UUID.randomUUID(), text, null), Duration.ofMinutes(2), 32000, null);
    }

    private List<UUID> ids(List<ChatSession> list) {
        return list.stream().map(ChatSession::id).toList();
    }

    private String key(UUID artifact) {
        return jdbc.sql("SELECT object_key FROM chat_file_artifact WHERE id = :id")
                .param("id", artifact).query(String.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private ObjectMetadata metadata(ObjectKey key) throws NoSuchAlgorithmException {
        byte[] bytes = stored.get(key.value());
        return bytes == null ? null
                : new ObjectMetadata(bytes.length, types.getOrDefault(key.value(), "text/csv"), sha256(bytes));
    }

    private static ContentSha256 sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private void seedTenant() {
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference)
                VALUES(:id,:slug,'Lifecycle','ACTIVE','test')
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
