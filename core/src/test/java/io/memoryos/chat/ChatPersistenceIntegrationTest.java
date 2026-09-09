package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.TestDatabase;
import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.application.DefaultChatSessionService;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.persistence.JpaTenantRepository;

import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatPersistenceIntegrationTest {
    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private ChatSessionService sessions;
    private ChatTurnPersistence turns;
    private TransactionTemplate tx;
    private ActorId owner;
    private ActorId other;
    private UUID tenant;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        tx = new TransactionTemplate(jpa.transactionManager());
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var repository = new JdbcChatRepository(jdbc);
        sessions = TestDatabase.transactionalProxy(new DefaultChatSessionService(tenants, repository, new PersonaProperties()),
                ChatSessionService.class, jpa.transactionManager());
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(jpa.transactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(new ChatTurnPersistence(tenants, repository, new PersonaProperties()));
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        turns = (ChatTurnPersistence) factory.getProxy();
        tenant = tenant();
        owner = member(tenant);
        other = member(tenant);
    }

    @Test
    void oversizedQuestionRollsBackBeforeTreeAdvancesAndBuiltinConfigRefreshesOnSend() {
        var session = sessions.create(owner, "Validation");
        var request = UUID.randomUUID();
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(), session.rootMessageId(), request,
                "Question ".repeat(500), Duration.ofMinutes(2), 100));
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        jdbc.sql("UPDATE persona SET model = 'obsolete-model' WHERE id = :id").param("id", session.personaId()).update();
        var reservation = turns.reserve(owner, session.id(), session.rootMessageId(), request, "Question", Duration.ofMinutes(2), 32000);
        assertEquals("gpt-5-mini", turns.loadContext(owner, session.id(), reservation).model());
    }

    @AfterEach
    void close() {
        if (jpa != null) jpa.close();
    }

    @Test
    void expiredNullReplyDoesNotTruncateAncestorContext() {
        var session = sessions.create(owner, "Context after expiry");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Earlier question");
        jdbc.sql("UPDATE chat_message SET deadline_at = CURRENT_TIMESTAMP - INTERVAL '10 seconds' WHERE id = :id")
                .param("id", first.assistantMessageId()).update();
        turns.expireRuns();
        assertEquals(ChatMessage.Status.FAILED, turns.authorizeReply(owner, session.id(), first.assistantMessageId()));
        var next = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Newest question");
        var context = turns.loadContext(owner, session.id(), next);
        assertEquals(List.of(next.userMessageId(), first.assistantMessageId(), first.userMessageId()),
                context.newestFirst().stream().map(ChatMessage::id).toList());
    }

    @Test
    void authorizedCanceledOutcomeCannotBeOverwrittenByLateCompletion() {
        var session = sessions.create(owner, "Stop");
        var reserved = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        assertThrows(ChatException.class, () -> turns.authorizeReply(other, session.id(), reserved.assistantMessageId()));
        assertEquals(ChatMessage.Status.RUNNING, turns.authorizeReply(owner, session.id(), reserved.assistantMessageId()));
        assertTrue(turns.finish(session.id(), reserved.assistantMessageId(), ChatMessage.Status.CANCELED,
                "partial", null, null, null, null, null));
        assertEquals(ChatMessage.Status.CANCELED, sessions.history(owner, session.id(), null, 20).getLast().status());
        assertEquals(ChatMessage.Status.CANCELED, turns.authorizeReply(owner, session.id(), reserved.assistantMessageId()));
        assertFalse(turns.finish(session.id(), reserved.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "late", null, null, null, null, null));
    }

    @Test
    void interruptedRunsExpireAndCannotBeResurrected() {
        var session = sessions.create(owner, "Interrupted");
        var reserved = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        jdbc.sql("UPDATE chat_message SET deadline_at = clock_timestamp() - interval '10 seconds' WHERE id = :id")
                .param("id", reserved.assistantMessageId()).update();
        assertEquals(1, turns.expireRuns());
        assertEquals(ChatMessage.Status.FAILED, sessions.history(owner, session.id(), null, 20).getLast().status());
        assertFalse(turns.finish(session.id(), reserved.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "late", null, null, null, null, null));
    }

    @Test
    void createsPrivateSessionWithOneRootAndSharedDefaultPersona() {
        var first = sessions.create(owner, "First");
        var second = sessions.create(owner, "Second");
        assertEquals(first.personaId(), second.personaId());
        assertEquals(first, sessions.get(owner, first.id()));
        assertTrue(sessions.history(owner, first.id(), null, 20).isEmpty());
        assertEquals(2, sessions.list(owner, 0, 30).size());
        assertTrue(sessions.list(other, 0, 30).isEmpty());
        assertEquals("CHAT_UNAVAILABLE", assertThrows(ChatException.class, () -> sessions.get(other, first.id())).code());
        // The deployment schema permits one Tenant; verify the repository still scopes by its ID.
        assertTrue(new JdbcChatRepository(jdbc).findOwned(new io.memoryos.iam.TenantId(UUID.randomUUID()),
                owner, first.id(), false).isEmpty());
        jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor")
                .param("actor", owner.value()).update();
        assertThrows(ChatException.class, () -> sessions.get(owner, first.id()));
        assertThrows(ChatException.class, () -> sessions.create(owner, "Denied"));
    }

    @Test
    void reservesPairOnceAndPreservesHistoryCursorAndPartialOutcome() {
        var session = sessions.create(owner, "History");
        var request = UUID.randomUUID();
        var first = reserve(session, session.rootMessageId(), request, "Question");
        var duplicate = reserve(session, session.rootMessageId(), request, "Question");
        assertTrue(first.created());
        assertFalse(duplicate.created());
        assertEquals(first.assistantMessageId(), duplicate.assistantMessageId());
        assertThrows(ChatException.class, () -> reserve(session, session.rootMessageId(), request, "Changed"));
        assertTrue(turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.CANCELED, "Partial"));
        assertFalse(turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Late"));
        var page = sessions.history(owner, session.id(), null, 1);
        assertEquals(first.userMessageId(), page.getFirst().id());
        var next = sessions.history(owner, session.id(), page.getFirst().id(), 1);
        assertEquals("Partial", next.getFirst().content());
        assertEquals(ChatMessage.Status.CANCELED, next.getFirst().status());
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Next");
        assertEquals(first.assistantMessageId(), sessions.history(owner, session.id(), first.assistantMessageId(), 10)
                .getFirst().parentMessageId());
        assertNotEquals(first.assistantMessageId(), second.assistantMessageId());
        assertFalse(reserve(session, session.rootMessageId(), request, "Question").created());
    }

    @Test
    void serializesConcurrentSendsAndTerminalWinners() throws Exception {
        var session = sessions.create(owner, "Race");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var request = UUID.randomUUID();
            var a = executor.submit(() -> {
                start.await();
                return reserve(session, session.rootMessageId(), request, "Same");
            });
            var b = executor.submit(() -> {
                start.await();
                return reserve(session, session.rootMessageId(), request, "Same");
            });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertEquals(first.assistantMessageId(), second.assistantMessageId());
            assertNotEquals(first.created(), second.created());
            assertThrows(ChatException.class, () -> reserve(session, session.rootMessageId(), UUID.randomUUID(), "New"));
            var finish = new CountDownLatch(1);
            var complete = executor.submit(() -> {
                finish.await();
                return turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Done");
            });
            var cancel = executor.submit(() -> {
                finish.await();
                return turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.CANCELED, "Partial");
            });
            finish.countDown();
            assertNotEquals(complete.get(10, TimeUnit.SECONDS), cancel.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsCrossSessionParentAndCursorAndRollsBackWholePair() {
        var first = sessions.create(owner, "First");
        var second = sessions.create(owner, "Second");
        assertThrows(ChatException.class, () -> reserve(first, second.rootMessageId(), UUID.randomUUID(), "Wrong parent"));
        assertThrows(ChatException.class, () -> sessions.history(owner, first.id(), second.rootMessageId(), 10));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(_ -> {
            reserve(first, first.rootMessageId(), UUID.randomUUID(), "Rollback");
            throw new IllegalStateException("abort");
        }));
        assertTrue(sessions.history(owner, first.id(), null, 10).isEmpty());
        assertTrue(reserve(first, first.rootMessageId(), UUID.randomUUID(), "After rollback").created());
    }

    @Test
    void databaseEnforcesParentSelectedChildRootAndSingleActiveReply() {
        var first = sessions.create(owner, "First");
        var second = sessions.create(owner, "Second");
        var pair = reserve(first, first.rootMessageId(), UUID.randomUUID(), "Question");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO chat_message(id, session_id, parent_message_id, role, status, deadline_at)
                VALUES (:id, :session, :parent, 'ASSISTANT', 'RUNNING', CURRENT_TIMESTAMP + INTERVAL '1 minute')
                """).param("id", UUID.randomUUID()).param("session", first.id()).param("parent", pair.userMessageId()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO chat_message(id, session_id, role, status, finished_at)
                VALUES (:id, :session, 'ROOT', 'COMPLETED', CURRENT_TIMESTAMP)
                """).param("id", UUID.randomUUID()).param("session", first.id()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE chat_message SET parent_message_id = :parent WHERE id = :id
                """).param("parent", second.rootMessageId()).param("id", pair.userMessageId()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE chat_message SET latest_child_message_id = :child WHERE id = :id
                """).param("child", pair.assistantMessageId()).param("id", first.rootMessageId()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE chat_message SET original_assistant_message_id = :original WHERE id = :id
                """).param("original", second.rootMessageId()).param("id", pair.userMessageId()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                UPDATE chat_message SET original_assistant_message_id = NULL WHERE id = :id
                """).param("id", pair.userMessageId()).update());
    }

    @Test
    void selectedBranchSkipsOlderAnswersWithoutDeletingThem() {
        var session = sessions.create(owner, "Branches");
        var request = UUID.randomUUID();
        var first = reserve(session, session.rootMessageId(), request, "Question");
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Old answer");
        UUID replacement = UUID.randomUUID();
        tx.executeWithoutResult(_ -> {
            jdbc.sql("""
                    INSERT INTO chat_message(id, session_id, parent_message_id, role, content, status, deadline_at, finished_at)
                    VALUES (:id, :session, :parent, 'ASSISTANT', 'Selected answer', 'COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """).param("id", replacement).param("session", session.id()).param("parent", first.userMessageId()).update();
            jdbc.sql("UPDATE chat_message SET latest_child_message_id = :child WHERE id = :parent")
                    .param("child", replacement).param("parent", first.userMessageId()).update();
        });
        var history = sessions.history(owner, session.id(), null, 10);
        assertEquals(2, history.size());
        assertEquals(replacement, history.getLast().id());
        assertEquals("Selected answer", history.getLast().content());
        assertThrows(ChatException.class, () -> sessions.history(owner, session.id(), first.assistantMessageId(), 10));
        assertEquals("Old answer", new JdbcChatRepository(jdbc).message(session.id(), first.assistantMessageId()).orElseThrow().content());
        var continuation = reserve(session, replacement, UUID.randomUUID(), "Continue selected branch");
        assertTrue(continuation.created());
        long count = new JdbcChatRepository(jdbc).messageCount(session.id());
        var retry = reserve(session, session.rootMessageId(), request, "Question");
        assertFalse(retry.created());
        assertEquals(first.userMessageId(), retry.userMessageId());
        assertEquals(first.assistantMessageId(), retry.assistantMessageId());
        assertEquals(count, new JdbcChatRepository(jdbc).messageCount(session.id()));
        var selected = sessions.history(owner, session.id(), null, 10);
        assertEquals(replacement, selected.get(1).id());
        assertEquals(continuation.assistantMessageId(), selected.getLast().id());
        assertThrows(ChatException.class, () -> reserve(session, session.rootMessageId(), request, "Changed"));
    }

    @Test
    void deadlineRejectsLateCompletionAndRetainsPartialFailure() {
        var session = sessions.create(owner, "Deadline");
        var pair = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        jdbc.sql("UPDATE chat_message SET deadline_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id")
                .param("id", pair.assistantMessageId()).update();
        assertTrue(turns.finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Partial"));
        assertEquals(ChatMessage.Status.FAILED, sessions.history(owner, session.id(), null, 20).getLast().status());
        assertFalse(turns.finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Late"));
    }

    @Test
    void membershipGuardWaitsForRevocationAndThenDeniesWrite() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var revoke = executor.submit(() -> tx.executeWithoutResult(_ -> {
                new IamLockRepository(jdbc).lockTenant(new io.memoryos.iam.TenantId(tenant));
                jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor")
                        .param("actor", owner.value()).update();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var writer = executor.submit(() -> sessions.create(owner, "Denied after revoke"));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            revoke.get(10, TimeUnit.SECONDS);
            var denied = assertThrows(java.util.concurrent.ExecutionException.class, () -> writer.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ChatException.class, denied.getCause());
        }
    }

    private ChatTurnPersistence.Reservation reserve(ChatSession session, UUID parent, UUID request, String text) {
        return turns.reserve(owner, session.id(), parent, request, text, Duration.ofMinutes(2), 32000);
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Chat', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return id;
    }

    private ActorId member(UUID tenantId) {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenantId).param("actor", actor.value()).update();
        return actor;
    }
}
