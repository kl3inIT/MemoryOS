package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.TestDatabase;
import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.application.DefaultChatSessionService;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JpaPersonaRepository;
import io.memoryos.chat.persistence.JpaProjectRepository;
import io.memoryos.chat.persistence.JpaChatSharingRepository;
import io.memoryos.chat.persistence.JpaChatFeedbackRepository;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantId;
import java.util.Map;
import java.util.Set;
import static org.mockito.Mockito.*;
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
    private com.zaxxer.hikari.HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private ChatSessionService sessions;
    private ChatTurnPersistence turns;
    private TransactionTemplate tx;
    private ActorId owner;
    private ActorId other;
    private UUID tenant;
    private ChatPersonaService personas;
    private ChatProjectService projects;
    private ChatCollaborationService collaboration;
    private UUID sourceId;

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
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
        var authorization = mock(IamAuthorization.class);
        when(authorization.effectiveCapabilities(owner)).thenReturn(Set.of(IamCapability.MODELS_MANAGE));
        when(authorization.effectiveCapabilities(other)).thenReturn(Set.of());
        var models = mock(ModelCatalogService.class);
        when(models.availableModelsForPersona(any(), any())).thenReturn(List.of(new ModelCatalogService.AvailableModel(
                UUID.randomUUID(), UUID.randomUUID(), "Provider", "model", "Model",
                new ModelSettings.Capabilities(true, true, false, false), 32000, 4096, null, true)));
        var sources = mock(SourceSearchService.class); sourceId = UUID.randomUUID();
        when(sources.scope(any())).thenReturn(new SourceSearchScope(new TenantId(tenant), Map.of(sourceId, SourceType.FILE)));
        personas = service(new ChatPersonaService(tenants, authorization, repository, jpa.repository(JpaPersonaRepository.class),
                new PersonaProperties(), models, sources), ChatPersonaService.class);
        projects = service(new ChatProjectService(tenants, repository, jpa.repository(JpaProjectRepository.class), sessions), ChatProjectService.class);
        collaboration = service(new ChatCollaborationService(tenants, repository, jpa.repository(JpaChatSharingRepository.class),
                jpa.repository(JpaChatFeedbackRepository.class)), ChatCollaborationService.class);
    }

    private <T> T service(T target, Class<T> contract) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(jpa.transactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(interceptor);
        return contract.cast(factory.getProxy());
    }

    @Test
    void projectsAndPersonasApplyAtAdmissionAndPreserveHistoryAcrossEditsAndDeletion() {
        var project = projects.create(owner, new ChatProjectService.ProjectInput("Work", "Description", "PROJECT PROMPT"));
        assertThrows(ChatException.class, () -> projects.get(other, project.id()));
        var session = projects.createConversation(owner, project.id(), "Project chat");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        assertTrue(turns.loadContext(owner, session.id(), first).instructions().contains("PROJECT PROMPT"));
        var updated = projects.update(owner, project.id(), project.revision(), new ChatProjectService.ProjectInput("Work", "", "CHANGED PROJECT"));
        assertThrows(ChatException.class, () -> projects.update(owner, project.id(), project.revision(), new ChatProjectService.ProjectInput("Stale", "", "")));
        assertFalse(turns.loadContext(owner, session.id(), first).instructions().contains("CHANGED PROJECT"));
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer");
        var assistant = personas.create(owner, new ChatPersonaService.PersonaInput("Private", "", "", List.of("A starter"),
                List.of(sourceId), false, null, 8000, 1000));
        assertThrows(ChatException.class, () -> personas.get(other, assistant.id()));
        personas.select(owner, session.id(), assistant.id());
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Continue");
        var context = turns.loadContext(owner, session.id(), second);
        assertFalse(context.instructions().contains("PROJECT"));
        assertFalse(context.options().searchEnabled());
        assertEquals(List.of(sourceId), context.options().sourceIds());
        assertEquals(1000, context.options().outputTokenLimit());
        assertEquals(List.of("A starter"), personas.get(owner, assistant.id()).starterPrompts());
        assertThrows(ChatException.class, () -> personas.select(owner, session.id(), session.personaId()));
        turns.finish(session.id(), second.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer 2");
        projects.delete(owner, project.id(), updated.revision());
        assertNull(sessions.get(owner, session.id()).projectId());
        assertEquals(4, sessions.history(owner, session.id(), null, 100).size());
        personas.delete(owner, assistant.id(), assistant.revision());
        assertThrows(ChatException.class, () -> reserve(session, second.assistantMessageId(), UUID.randomUUID(), "Deleted assistant"));
        assertEquals(4, sessions.history(owner, session.id(), null, 100).size());
        personas.select(owner, session.id(), session.personaId());
        assertTrue(reserve(session, second.assistantMessageId(), UUID.randomUUID(), "Default again").created());
    }

    @Test
    void editAndRegenerateKeepOldBranchesAndCommandIdentityWithoutDuplicatingUserMessages() {
        var session = sessions.create(owner, "Versions");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "First question");
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "First answer");
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Follow-up");
        turns.finish(session.id(), second.assistantMessageId(), ChatMessage.Status.COMPLETED, "Second answer");
        var regenerate = new ChatCommand(ChatCommand.Operation.REGENERATE, first.userMessageId(), UUID.randomUUID(), "", null);
        var retryAnswer = turns.reserve(owner, session.id(), regenerate, Duration.ofMinutes(2), 32000, null);
        assertEquals(first.userMessageId(), retryAnswer.userMessageId());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM chat_message WHERE session_id=:session AND role='USER'")
                .param("session", session.id()).query(Long.class).single());
        assertThrows(ChatException.class, () -> sessions.selectBranch(owner, session.id(), first.assistantMessageId(), retryAnswer.assistantMessageId()));
        turns.finish(session.id(), retryAnswer.assistantMessageId(), ChatMessage.Status.COMPLETED, "Alternative");
        assertEquals(2, sessions.history(owner, session.id(), null, 100).size());
        sessions.selectBranch(owner, session.id(), first.assistantMessageId(), retryAnswer.assistantMessageId());
        assertEquals(4, sessions.history(owner, session.id(), null, 100).size());
        var edit = new ChatCommand(ChatCommand.Operation.EDIT, first.userMessageId(), UUID.randomUUID(), "Edited question", null);
        var edited = turns.reserve(owner, session.id(), edit, Duration.ofMinutes(2), 32000, null);
        assertNotEquals(first.userMessageId(), edited.userMessageId());
        assertEquals("Edited question", turns.loadContext(owner, session.id(), edited).newestFirst().getFirst().content());
        assertEquals(1, turns.loadContext(owner, session.id(), edited).newestFirst().size());
        turns.finish(session.id(), edited.assistantMessageId(), ChatMessage.Status.COMPLETED, "Edited answer");
        assertEquals(retryAnswer.assistantMessageId(), turns.reserve(owner, session.id(), regenerate, Duration.ofMinutes(2), 32000, null).assistantMessageId());
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(),
                new ChatCommand(ChatCommand.Operation.EDIT, first.userMessageId(), regenerate.requestId(), "Other", null), Duration.ofMinutes(2), 32000, null));
        sessions.selectBranch(owner, session.id(), first.userMessageId(), edited.userMessageId());
        assertEquals(second.assistantMessageId(), sessions.history(owner, session.id(), null, 100).getLast().id());
    }

    @Test
    void sharingIsReadOnlyTenantAccessFeedbackStaysOnOutputAndDeletionWinsLateCompletion() {
        var session = sessions.create(owner, "Sharing");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer");
        assertThrows(ChatException.class, () -> collaboration.shared(other, session.id()));
        var sharing = collaboration.share(owner, session.id(), true, 0);
        assertTrue(sharing.revision() > 0);
        assertEquals(2, collaboration.sharedHistory(other, session.id(), null, 100).size());
        assertThrows(ChatException.class, () -> collaboration.share(other, session.id(), false, sharing.revision()));
        assertThrows(ChatException.class, () -> collaboration.feedback(other, session.id(), first.assistantMessageId(), true, "", ""));
        collaboration.feedback(owner, session.id(), first.assistantMessageId(), true, "Useful", "");
        collaboration.feedback(owner, session.id(), first.assistantMessageId(), false, "Incomplete", "missing_context");
        assertEquals(1, collaboration.feedback(owner, session.id(), List.of(first.assistantMessageId())).size());
        assertEquals(Boolean.FALSE, collaboration.feedback(owner, session.id(), List.of(first.assistantMessageId())).getFirst().positive());
        var regeneration = turns.reserve(owner, session.id(), new ChatCommand(ChatCommand.Operation.REGENERATE, first.userMessageId(),
                UUID.randomUUID(), "", null), Duration.ofMinutes(2), 32000, null);
        assertTrue(collaboration.feedback(owner, session.id(), List.of(regeneration.assistantMessageId())).isEmpty());
        collaboration.removeFeedback(owner, session.id(), first.assistantMessageId());
        collaboration.removeFeedback(owner, session.id(), first.assistantMessageId());
        collaboration.share(owner, session.id(), false, sharing.revision());
        assertThrows(ChatException.class, () -> collaboration.shared(other, session.id()));
        turns.delete(owner, session.id());
        assertFalse(turns.finish(session.id(), regeneration.assistantMessageId(), ChatMessage.Status.COMPLETED, "Late answer"));
        assertThrows(ChatException.class, () -> sessions.get(owner, session.id()));
        assertTrue(sessions.list(owner, 0, 100).isEmpty());
    }

    @Test
    void oversizedQuestionRollsBackBeforeTreeAdvancesAndBuiltinConfigurationSurvivesSend() {
        var session = sessions.create(owner, "Validation");
        var request = UUID.randomUUID();
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(), session.rootMessageId(), request,
                "Question ".repeat(500), Duration.ofMinutes(2), 100));
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        jdbc.sql("UPDATE persona SET model = 'obsolete-model' WHERE id = :id").param("id", session.personaId()).update();
        var reservation = turns.reserve(owner, session.id(), session.rootMessageId(), request, "Question", Duration.ofMinutes(2), 32000);
        assertEquals("obsolete-model", turns.loadContext(owner, session.id(), reservation).model());
    }

    @AfterEach
    void close() {
        try { if (jpa != null) jpa.close(); }
        finally { if (dataSource != null) dataSource.close(); }
    }

    @Test
    void sourcesCommitWithTheTerminalWinnerAndRemainHistoricalEvidence() {
        var session = sessions.create(owner, "Evidence");
        var pair = turns.reserve(owner, session.id(), session.rootMessageId(), UUID.randomUUID(), "Leave policy?", Duration.ofMinutes(2), 32000);
        var source = new ChatSource(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 2, 2,
                List.of(new ChatSource.Provenance(2, "[{\"page\":3}]")));
        turns.finishAndRead(session.id(), pair.assistantMessageId(), ChatMessage.Status.CANCELED,
                "Twelve days [1]", null, "model", 10L, 4L, null, List.of(source));
        turns.finishAndRead(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "Late answer", null, "model", 20L, 5L, null, List.of());
        var saved = sessions.history(owner, session.id(), null, 100).getLast();
        assertEquals(ChatMessage.Status.CANCELED, saved.status());
        assertEquals("Twelve days [1]", saved.content());
        assertEquals(List.of(source), saved.sources());
        // Source IDs intentionally need no live document FK: reindex/delete does not rewrite old answers.
        assertThrows(ChatException.class, () -> sessions.history(other, session.id(), null, 100));
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
    void builtinPersonaSnapshotBlocksAnotherOwnersEditUntilAdmissionReadCompletes() throws Exception {
        var session = sessions.create(other, "Shared builtin");
        var before = personas.get(owner, session.personaId());
        var repository = new JdbcChatRepository(jdbc);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = executor.submit(() -> tx.execute(_ -> {
                repository.lockOwner(new TenantId(tenant), other);
                var snapshot = repository.persona(session.id(), true);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                assertEquals(snapshot, repository.persona(session.id(), true));
                return snapshot;
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var writer = executor.submit(() -> personas.update(owner, before.id(), before.revision(),
                        new ChatPersonaService.PersonaInput(before.name(), "", "Updated builtin", List.of(), List.of(sourceId),
                                false, null, null, null)));
                try {
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
                } finally {
                    release.countDown();
                }
                writer.get(10, TimeUnit.SECONDS);
                var snapshot = reader.get(10, TimeUnit.SECONDS);
                var after = tx.execute(_ -> repository.persona(session.id(), true));
                assertNotNull(snapshot);
                assertNotNull(after);
                assertNotEquals(snapshot.revision(), after.revision());
                assertEquals("Updated builtin", after.instructions());
                assertEquals(List.of(sourceId), after.options().sourceIds());
            } finally {
                release.countDown();
            }
        }
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
