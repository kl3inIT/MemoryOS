package io.memoryos.chat;

import com.embabel.chat.Message;
import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.StatementCounter;
import io.memoryos.ai.AiException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import io.memoryos.TestDatabase;
import io.memoryos.chat.image.persistence.JdbcImageArtifactRepository;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.chat.persona.persistence.JdbcDocumentSetRepository;
import io.memoryos.chat.persona.persistence.PersonaRevisions;
import io.memoryos.chat.prompts.ChatPrompts;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.session.DefaultChatSessionService;
import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelSettings;
import io.memoryos.ai.ReasoningEffort;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.persona.persistence.JdbcAgentRepository;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.session.persistence.JdbcChatSummaryRepository;
import io.memoryos.chat.session.persistence.JdbcChatSearchRepository;
import io.memoryos.chat.persona.persistence.JdbcPromptShortcutRepository;
import io.memoryos.chat.session.persistence.JpaChatFeedbackRepository;
import io.memoryos.chat.session.persistence.JpaChatSharingRepository;
import io.memoryos.chat.persona.persistence.JpaPersonaRepository;
import io.memoryos.chat.project.persistence.JpaProjectRepository;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.ActorLanguageService;
import io.memoryos.iam.identity.persistence.ActorRefreshImpl;
import io.memoryos.iam.identity.persistence.JpaActorRepository;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.shared.Tokenizers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import io.memoryos.library.UserFileProperties;
import io.memoryos.library.LibraryStorageProperties;
import io.memoryos.library.StorageQuotaService;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.chat.files.ChatFileAttachments;
import io.memoryos.chat.files.persistence.JdbcChatFileAttachmentRepository;
import io.memoryos.library.LibraryTrashProperties;
import io.memoryos.library.UserFileContentService;
import io.memoryos.library.UserFileService;
import io.memoryos.library.LibraryFile;
import io.memoryos.library.UserFile;
import io.memoryos.chat.files.persistence.JdbcChatArtifactRepository;
import io.memoryos.library.FileAttachments;
import io.memoryos.library.LibraryException;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatPersistenceIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private ChatSessionService sessions;
    private ChatTurnPersistence turns;

    /** What the Worker runs to end the turns of a process that died. */
    private ChatRunReconciliation reconciliation() {
        return new ChatRunReconciliation(new JdbcChatRepository(jdbc));
    }
    private TransactionTemplate tx;
    private ActorId owner;
    private ActorId other;
    private UUID tenant;
    private ChatPersonaService personas;
    private ChatProjectService projects;
    private ChatCollaborationService collaboration;
    private UUID sourceId;
    private IamAuthorization authorization;
    private ChatPromptShortcutService shortcuts;
    private DocumentSetService documentSets;
    private SourceSearchService sourceScope;
    private StatementCounter statements;
    private ChatModelAccess models;

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        statements = new StatementCounter(dataSource);
        jdbc = JdbcClient.create(statements);
        jpa = TestDatabase.jpa(statements);
        tx = new TransactionTemplate(jpa.transactionManager());
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var repository = new JdbcChatRepository(jdbc);
        authorization = mock(IamAuthorization.class);
        sessions = TestDatabase.transactionalProxy(new DefaultChatSessionService(tenants, authorization, repository, new JdbcChatSearchRepository(jdbc)),
                ChatSessionService.class, jpa.transactionManager());
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(jpa.transactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var fileService = new UserFileService(tenants, new JdbcUserFileRepository(jdbc), new ChatFileAttachments(new JdbcChatFileAttachmentRepository(jdbc)),
                mock(ObjectUploadService.class),
                new UserFileProperties(104857600, 262144000),
                new StorageQuotaService(tenants,
                new LibraryStorageProperties(0), new JdbcLibraryRepository(jdbc)),
                new LibraryTrashProperties(Duration.ZERO),
                jpa.transactionManager());
        var factory = new ProxyFactory(new ChatTurnPersistence(tenants, authorization, repository, fileService,
                new ActorLanguageService(jpa.repository(JpaActorRepository.class,
                        RepositoryFragments.just(new ActorRefreshImpl(jpa.entityManager()))), tenants),
                new JdbcImageArtifactRepository(jdbc), new JdbcChatSummaryRepository(jdbc)));
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        turns = (ChatTurnPersistence) factory.getProxy();
        tenant = tenant();
        owner = member(tenant);
        other = member(tenant);
        when(authorization.effectiveCapabilities(owner)).thenReturn(Set.of(IamCapability.MODELS_MANAGE, IamCapability.AGENTS_MANAGE, IamCapability.AGENTS_CREATE));
        when(authorization.effectiveCapabilities(other)).thenReturn(Set.of());
        models = mock(ChatModelAccess.class);
        when(models.availableModelsForPersona(any(), any())).thenReturn(List.of(new ModelCatalogService.AvailableModel(
                UUID.randomUUID(), UUID.randomUUID(), "Provider", "model", "Model",
                new ModelSettings.Capabilities(true, true, false, false, false), 32000, 4096, null, true)));
        var sources = mock(SourceSearchService.class); sourceScope = sources; sourceId = UUID.randomUUID();
        when(sources.scope(any())).thenAnswer(call -> new SourceSearchScope(new TenantId(tenant), call.getArgument(0), Map.of(sourceId, SourceType.FILE)));
        var agentRows = new JdbcAgentRepository(jdbc);
        var documentSetRows = new JdbcDocumentSetRepository(jdbc);
        documentSets = service(new DocumentSetService(tenants, authorization, sources, agentRows, documentSetRows), DocumentSetService.class);
        personas = service(new ChatPersonaService(tenants, authorization, repository, jpa.repository(JpaPersonaRepository.class),
                agentRows, new PersonaRevisions(jpa.entityManager()),
                models, sources, documentSets, documentSetRows, fileService,
                mock(UserFileContentService.class)), ChatPersonaService.class);
        projects = service(new ChatProjectService(tenants, authorization, repository, jpa.repository(JpaProjectRepository.class), sessions, fileService), ChatProjectService.class);
        shortcuts = service(new ChatPromptShortcutService(tenants, authorization, repository,
                new JdbcPromptShortcutRepository(jdbc)), ChatPromptShortcutService.class);
        collaboration = service(new ChatCollaborationService(tenants, authorization, repository, jpa.repository(JpaChatSharingRepository.class),
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
    void documentSetsShareAndAttachToPersonasWithoutReplacingDirectSources() {
        var set = documentSets.create(owner, new DocumentSetService.Input("Finance", "Monthly reports", List.of(), false));
        var agent = personas.create(owner, new ChatPersonaService.PersonaInput("Finance assistant", null, "", "Use reports.", null,
                List.of(), List.of(), List.of(set.id()), Set.of("search"), null, null, null, List.of(), null, null, null,
                null, null));

        assertEquals(List.of(set.id()), personas.get(owner, agent.id()).documentSetIds());
        assertEquals(List.of("Finance"), personas.get(owner, agent.id()).documentSets().stream().map(ChatPersonaService.DocumentSetRef::name).toList());

        documentSets.share(owner, set.id(), set.revision(), new DocumentSetService.DocumentSetSharingInput(List.of(other.value()), List.of()));
        assertEquals(List.of(set.id()), documentSets.list(other, 0, 100).stream().map(DocumentSetService.View::id).toList());
    }

    @Test
    void publicDocumentSetsAreUsableWithoutSharesAndHideSourcesTheViewerCannotSelect() {
        source(sourceId);
        var set = documentSets.create(owner, new DocumentSetService.Input("Company reports", "", List.of(sourceId), true));
        assertTrue(set.isPublic());
        // The viewer holds no Source authority, so the set narrows for them without naming its Sources.
        when(sourceScope.scope(other)).thenReturn(new SourceSearchScope(new TenantId(tenant), other, Map.of()));

        var seen = documentSets.list(other, 0, 100);
        assertEquals(List.of(set.id()), seen.stream().map(DocumentSetService.View::id).toList());
        assertEquals(List.of(), seen.getFirst().sources());
        assertEquals(1, seen.getFirst().hiddenSources());
        assertFalse(seen.getFirst().permissions().edit());
        assertEquals(List.of(new DocumentSetService.SourceRef(sourceId, "")), documentSets.get(owner, set.id()).sources());

        var privateAgain = documentSets.update(owner, set.id(), documentSets.get(owner, set.id()).revision(),
                new DocumentSetService.Input("Company reports", "", List.of(sourceId), false));
        assertFalse(privateAgain.isPublic());
        assertEquals(List.of(), documentSets.list(other, 0, 100));
    }

    @Test
    void anAgentWhoseDocumentSetIsUnusableSearchesNothingInsteadOfEveryAuthorizedSource() {
        source(sourceId);
        when(authorization.effectiveCapabilities(other)).thenReturn(Set.of(IamCapability.CHAT_READ, IamCapability.CHAT_WRITE));
        var set = documentSets.create(owner, new DocumentSetService.Input("Owner only", "", List.of(sourceId), false));
        var agent = personas.create(owner, new ChatPersonaService.PersonaInput("Reports", null, "", "Use reports.", null,
                List.of(), List.of(), List.of(set.id()), Set.of("search"), null, null, null, List.of(), null, null, null,
                null, null));
        personas.share(owner, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(
                List.of(), List.of(), true, AgentPermission.VIEWER));

        var session = sessions.create(other, "Shared agent");
        personas.select(other, session.id(), agent.id());
        var options = tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(session.id(), false, false)).options();

        assertTrue(options.sourcesRestricted(), "The agent attaches a Document Set, so the turn stays restricted");
        assertEquals(List.of(), options.sourceIds(), "The viewer cannot use the Set, so it contributes no Source");
        assertEquals(List.of(), options.sourceAllowlist(), "An unusable attachment searches nothing, never everything");

        var ownerSession = sessions.create(owner, "Own agent");
        personas.select(owner, ownerSession.id(), agent.id());
        assertEquals(List.of(sourceId), tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(ownerSession.id(), false, false))
                .options().sourceAllowlist());
    }

    /** A Source row the Document Set foreign keys accept. */
    private void source(UUID id) {
        UUID credential = UUID.randomUUID();
        jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,'Test','NO_AUTH','ACTIVE')")
                .param("id", credential).param("tenant", tenant).update();
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,'Test','FILE','ACTIVE')")
                .param("id", id).param("tenant", tenant).update();
        jdbc.sql("""
                        INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                        VALUES(:id,:tenant,:id,:credential,'PUBLIC','ACTIVE')
                        """).param("id", id).param("tenant", tenant).param("credential", credential).update();
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
        var assistant = personas.create(owner, input("Private", List.of("A starter"), List.of(sourceId), false, null));
        assertThrows(ChatException.class, () -> personas.get(other, assistant.id()));
        personas.select(owner, session.id(), assistant.id());
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Continue");
        var context = turns.loadContext(owner, session.id(), second);
        assertFalse(context.instructions().contains("PROJECT"));
        assertFalse(context.options().searchEnabled());
        assertEquals(List.of(sourceId), context.options().sourceIds());
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
    void projectsListInTheChosenOrderANewOneComesFirstAndAnEditOrReorderMovesNothing() {
        var work = projects.create(owner, new ChatProjectService.ProjectInput("Work", "", ""));
        var home = projects.create(owner, new ChatProjectService.ProjectInput("Home", "", ""));
        var travel = projects.create(owner, new ChatProjectService.ProjectInput("Travel", "", ""));
        var foreign = projects.create(other, new ChatProjectService.ProjectInput("Foreign", "", ""));
        assertEquals(List.of(travel.id(), home.id(), work.id()), projectIds(owner));
        assertEquals(List.of(foreign.id()), projectIds(other));

        // An edit changes updatedAt but never the place; the list no longer sorts by recency.
        projects.update(owner, work.id(), work.revision(), new ChatProjectService.ProjectInput("Work 2", "", ""));
        assertEquals(List.of(travel.id(), home.id(), work.id()), projectIds(owner));

        var before = projects.list(owner, 0, 100);
        statements.reset();
        projects.reorder(owner, List.of(work.id(), travel.id(), home.id()));
        // Every place is written by one statement, however many Projects the person has.
        assertEquals(1, statements.count(sql -> sql.toLowerCase(Locale.ROOT).startsWith("update chat_project")),
                statements.statements().toString());
        var after = projects.list(owner, 0, 100);
        assertEquals(List.of(work.id(), travel.id(), home.id()), after.stream().map(ChatProjectService.ProjectView::id).toList());
        for (var project : after) {
            var was = before.stream().filter(p -> p.id().equals(project.id())).findFirst().orElseThrow();
            assertEquals(was.revision(), project.revision());
            assertEquals(was.updatedAt(), project.updatedAt());
        }
        assertEquals(List.of(0, 1, 2), jdbc.sql("SELECT position FROM chat_project WHERE owner_actor_id=:owner ORDER BY position")
                .param("owner", owner.value()).query(Integer.class).list());

        // A new project takes one less than the lowest place, without rewriting the others.
        var newest = projects.create(owner, new ChatProjectService.ProjectInput("Newest", "", ""));
        assertEquals(List.of(newest.id(), work.id(), travel.id(), home.id()), projectIds(owner));
        assertEquals(-1, (int) jdbc.sql("SELECT position FROM chat_project WHERE id=:id").param("id", newest.id()).query(Integer.class).single());
        // The revision a client holds still works after a reorder.
        projects.update(owner, work.id(), after.getFirst().revision(), new ChatProjectService.ProjectInput("Work 3", "", ""));
    }

    @Test
    void reorderingRequiresExactlyTheActorsProjectsAndChangesNothingOtherwise() {
        var first = projects.create(owner, new ChatProjectService.ProjectInput("First", "", ""));
        var second = projects.create(owner, new ChatProjectService.ProjectInput("Second", "", ""));
        var foreign = projects.create(other, new ChatProjectService.ProjectInput("Foreign", "", ""));
        var expected = List.of(second.id(), first.id());

        for (var bad : List.of(List.of(first.id()), List.of(first.id(), first.id()), List.of(first.id(), second.id(), foreign.id()),
                List.of(first.id(), foreign.id()), List.of(first.id(), second.id(), UUID.randomUUID()), List.<UUID>of())) {
            assertEquals("CHAT_CONFLICT", assertThrows(ChatException.class, () -> projects.reorder(owner, bad)).code());
            assertEquals(expected, projectIds(owner));
        }
        assertEquals(List.of(foreign.id()), projectIds(other));
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> projects.reorder(owner, null)).code());
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class,
                () -> projects.reorder(owner, Collections.nCopies(1001, first.id()))).code());
        var withNull = new ArrayList<UUID>(); withNull.add(first.id()); withNull.add(null);
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> projects.reorder(owner, withNull)).code());

        // A person without a project reorders the empty list; an empty list never hides a project.
        projects.reorder(member(tenant), List.of());
        projects.reorder(owner, List.of(first.id(), second.id()));
        assertEquals(List.of(first.id(), second.id()), projectIds(owner));
    }

    private List<UUID> projectIds(ActorId actor) {
        return projects.list(actor, 0, 100).stream().map(ChatProjectService.ProjectView::id).toList();
    }

    @Test
    void generatedImagesAreNamedInLaterContextAndEditSourcesStayInTheirSession() {
        var images = new JdbcImageArtifactRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Images");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Draw a man");
        var image = UUID.randomUUID();
        images.insert(scope, first.assistantMessageId(), image, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/image.png"), "image/png", ".png", 11, null, null, null);
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Here he is.");
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Make the shirt red");
        assertEquals(List.of(image), turns.loadContext(owner, session.id(), second).generatedImages().get(first.assistantMessageId()));

        var edited = UUID.randomUUID();
        images.insert(scope, second.assistantMessageId(), edited, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/edited.png"), "image/png", ".png", 12, null, image, null);
        assertEquals(image, jdbc.sql("SELECT source_artifact_id FROM chat_image_artifact WHERE id = :id")
                .param("id", edited).query(UUID.class).single());
        assertTrue(images.inSession(scope, owner, session.id(), image).isPresent());
        assertTrue(images.inSession(scope, other, session.id(), image).isEmpty());
        var elsewhere = sessions.create(owner, "Elsewhere");
        assertTrue(images.inSession(scope, owner, elsewhere.id(), image).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> images.insert(scope, second.assistantMessageId(), UUID.randomUUID(),
                UUID.randomUUID(), new ObjectKey("tenants/" + tenant + "/both.png"), "image/png", ".png", 13,
                null, image, UUID.randomUUID()));
    }

    @Test
    void anAnswersToolCallsReachTheNextTurnInOrderAndGoWithTheirMessage() {
        var session = sessions.create(owner, "Tools");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Clean this");
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Cleaned.");
        var calls = List.of(new ChatToolTranscript.Call("call_1", "bash", "{\"command\":\"python3 clean.py\"}", "saved"),
                new ChatToolTranscript.Call("call_2", "search_knowledge", "{}", null));
        turns.recordToolCalls(first.assistantMessageId(), calls);
        // Finishing again records nothing twice.
        turns.recordToolCalls(first.assistantMessageId(), calls);
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Add a chart");
        assertEquals(calls, turns.loadContext(owner, session.id(), second).toolCalls().get(first.assistantMessageId()));
        assertEquals(2, jdbc.sql("SELECT count(*) FROM chat_message_tool_calls").query(Long.class).single());
        jdbc.sql("DELETE FROM chat_session WHERE id = :id").param("id", session.id()).update();
        assertEquals(0, jdbc.sql("SELECT count(*) FROM chat_message_tool_calls").query(Long.class).single());
    }

    @Test
    void interpreterSettingRevisesAndGeneratedFilesServeOnlyTheirOwner() {
        var interpreter = new JdbcInterpreterRepository(jdbc);
        var scope = new TenantId(tenant);
        assertTrue(interpreter.setting(scope).isEmpty());
        assertEquals(new JdbcInterpreterRepository.Setting(true, 1), interpreter.save(scope, true));
        assertEquals(new JdbcInterpreterRepository.Setting(false, 2), interpreter.save(scope, false));

        var session = sessions.create(owner, "Code");
        var reply = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Make a chart");
        var file = UUID.randomUUID();
        interpreter.insertArtifact(scope, reply.assistantMessageId(), file, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/chart.png"), "chart.png", "image/png", 3,
                "{\"type\":\"pie\",\"title\":\"Doanh thu\",\"elements\":[]}");
        var owned = interpreter.ownedArtifact(scope, owner, file).orElseThrow();
        assertEquals("chart.png", owned.filename());
        assertEquals("image/png", owned.mediaType());
        assertTrue(interpreter.ownedArtifact(scope, other, file).isEmpty());
        // Chart data (V72) is served only to the owner and flagged on the message's files.
        assertTrue(interpreter.ownedChart(scope, owner, file).orElseThrow().contains("\"type\": \"pie\""));
        assertTrue(interpreter.ownedChart(scope, other, file).isEmpty());
        assertTrue(interpreter.byMessages(scope, List.of(reply.assistantMessageId())).get(reply.assistantMessageId()).getFirst().chart());
        // A converted presentation preview (V73) is recorded once; a concurrent second conversion is refused.
        assertTrue(interpreter.attachPreview(scope, file, UUID.randomUUID(), new ObjectKey("p/1"), 10));
        assertFalse(interpreter.attachPreview(scope, file, UUID.randomUUID(), new ObjectKey("p/2"), 10));
        assertEquals("p/1", interpreter.ownedArtifact(scope, owner, file).orElseThrow().previewKey().value());
        assertThrows(DataIntegrityViolationException.class, () -> interpreter.insertArtifact(scope,
                reply.assistantMessageId(), UUID.randomUUID(), UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/x.png"), "x.png", "image/png", 3, "[1]"));
        assertTrue(interpreter.ownedArtifact(new TenantId(UUID.randomUUID()), owner, file).isEmpty());
    }

    @Test
    void theFileLibraryUnionsEverySourceForItsOwnerAndHidesWhatWasDeleted() {
        var library = new JdbcLibraryRepository(jdbc);
        var artifacts = new JdbcChatArtifactRepository(jdbc);
        var interpreter = new JdbcInterpreterRepository(jdbc);
        var images = new JdbcImageArtifactRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Báo cáo");
        var reply = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Make a workbook");
        var upload = readyFile(owner);
        var generated = UUID.randomUUID();
        interpreter.insertArtifact(scope, reply.assistantMessageId(), generated, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/doanh-thu.xlsx"), "doanh-thu.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 2048, null);
        var image = UUID.randomUUID();
        images.insert(scope, reply.assistantMessageId(), image, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/pic.png"), "image/png", ".png", 512,
                "a red shirt", null, null);

        var all = library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50);
        assertEquals(3, all.totalCount());
        assertEquals(4 + 2048 + 512, all.totalBytes());
        assertEquals(List.of(LibraryFile.Source.IMAGE, LibraryFile.Source.GENERATED, LibraryFile.Source.UPLOAD),
                all.items().stream().map(LibraryFile::source).toList());
        var workbook = all.items().stream().filter(file -> file.id().equals(generated)).findFirst().orElseThrow();
        assertEquals(LibraryFile.Category.SPREADSHEET, workbook.category());
        assertEquals(session.id(), workbook.sessionId());
        assertEquals("Báo cáo", workbook.sessionTitle());
        // An upload belongs to its owner, not to one conversation.
        assertNull(all.items().stream().filter(file -> file.id().equals(upload)).findFirst().orElseThrow().sessionId());
        // A generated image is named and measured, and its prompt is searchable (V90).
        var picture = all.items().stream().filter(file -> file.id().equals(image)).findFirst().orElseThrow();
        assertTrue(picture.filename().startsWith("image-") && picture.filename().endsWith(".png"), picture.filename());
        assertEquals(512, picture.sizeBytes());
        assertEquals(List.of(image), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("red shirt", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50)));

        assertEquals(List.of(generated), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("doanh", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(generated), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of("GENERATED"), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(upload), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of("DOCUMENT"), null), LibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(generated, image, upload),
                ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.LARGEST, 0, 50)));
        var second = library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 1, 1);
        assertEquals(3, second.totalCount());
        assertEquals(List.of(generated), ids(second));
        // A page past the end still reports the filter's totals; they describe the filter, not the page.
        var beyond = library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 10, 50);
        assertEquals(List.of(), ids(beyond));
        assertEquals(3, beyond.totalCount());
        assertEquals(4 + 2048 + 512, beyond.totalBytes());
        // A search term is matched literally, not as an ILIKE pattern.
        assertEquals(List.of(), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("%", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(0, library.page(scope, other, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50).totalCount());

        // Deleting a generated file hides it everywhere and refuses a preview that was converting meanwhile.
        assertTrue(interpreter.markArtifactDeleted(scope, owner, generated, Duration.ZERO));
        assertFalse(interpreter.markArtifactDeleted(scope, other, generated, Duration.ZERO));
        assertTrue(interpreter.ownedArtifact(scope, owner, generated).isEmpty());
        assertFalse(interpreter.attachPreview(scope, generated, UUID.randomUUID(),
                new ObjectKey("p/late"), 10));
        assertTrue(images.markDeleted(scope, owner, image, Duration.ZERO));
        // Deleting again succeeds while another member still cannot delete the same image.
        assertTrue(images.markDeleted(scope, owner, image, Duration.ZERO));
        assertFalse(images.markDeleted(scope, other, image, Duration.ZERO));
        assertTrue(images.inSession(scope, owner, session.id(), image).isEmpty());
        assertEquals(List.of(upload), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50)));
        // History keeps both as tombstones so the answer does not silently lose its cards.
        assertTrue(interpreter.byMessages(scope, List.of(reply.assistantMessageId()))
                .get(reply.assistantMessageId()).getFirst().deleted());
        assertTrue(images.byMessages(scope, List.of(reply.assistantMessageId()), true)
                .get(reply.assistantMessageId()).getFirst().deleted());
        assertTrue(images.byMessages(scope, List.of(reply.assistantMessageId()), false).isEmpty());
        // Once the sweep has removed the row, the owner's repeated delete is still the outcome they asked for.
        jdbc.sql("DELETE FROM chat_image_artifact WHERE id=:id").param("id", image).update();
        assertTrue(images.markDeleted(scope, owner, image, Duration.ZERO));

        // Deleting the conversation withdraws its artifacts from the library; the upload is the owner's.
        var kept = sessions.create(owner, "Kept");
        var keptReply = reserve(kept, kept.rootMessageId(), UUID.randomUUID(), "One more");
        var keptFile = UUID.randomUUID();
        interpreter.insertArtifact(scope, keptReply.assistantMessageId(), keptFile, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/notes.pdf"), "notes.pdf", "application/pdf", 8, null);
        assertEquals(List.of(keptFile, upload), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50)));
        turns.delete(owner, kept.id());
        assertEquals(List.of(upload), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50)));

        // An upload a project holds is named rather than silently undeletable.
        var project = projects.create(owner, new ChatProjectService.ProjectInput("Kế hoạch", "", "", List.of(upload)));
        var usage = new JdbcChatFileAttachmentRepository(jdbc).holders(scope, List.of(upload));
        assertEquals(List.of("Kế hoạch"), usage.stream().map(FileAttachments.Holder::name).toList());
        assertEquals(LibraryFile.Usage.Kind.PROJECT, usage.getFirst().kind());
        assertEquals(project.id(), usage.getFirst().id());
    }

    @Test
    void aConversationsOwnFilesAreItsArtifactsAndTheUploadsAttachedInIt() {
        var library = new JdbcLibraryRepository(jdbc);
        var images = new JdbcImageArtifactRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Có tệp");
        var elsewhere = sessions.create(owner, "Nơi khác");
        var attached = readyFile(owner);
        var unattached = readyFile(owner);
        var reply = turns.reserve(owner, session.id(), ChatCommand.builder(ChatCommand.Operation.SEND,
                session.rootMessageId(), UUID.randomUUID(), "Xem tệp này")
                .fileIds(List.of(attached))
                .build(),
                Duration.ofMinutes(2), 32000, null);
        var image = UUID.randomUUID();
        images.insert(scope, reply.assistantMessageId(), image, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/in-session.png"), "image/png", ".png", 64,
                null, null, null);
        var otherReply = reserve(elsewhere, elsewhere.rootMessageId(), UUID.randomUUID(), "Khác");
        var otherImage = UUID.randomUUID();
        images.insert(scope, otherReply.assistantMessageId(), otherImage, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/elsewhere.png"), "image/png", ".png", 32,
                null, null, null);

        var inSession = library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), session.id()), LibraryFile.Sort.NEWEST, 0, 50);
        assertEquals(List.of(image, attached), ids(inSession));
        assertEquals(64 + 4, inSession.totalBytes());
        // Each row names the message to scroll to: the answer that made the image, the question that attached the file.
        assertEquals(reply.assistantMessageId(), inSession.items().get(0).messageId());
        assertNotNull(inSession.items().get(1).messageId());
        assertNotEquals(reply.assistantMessageId(), inSession.items().get(1).messageId());
        // The other conversation's image and an upload never attached here stay out.
        assertFalse(ids(inSession).contains(otherImage));
        assertFalse(ids(inSession).contains(unattached));
        // Without the filter both conversations' files are listed.
        assertTrue(ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null), LibraryFile.Sort.NEWEST, 0, 50))
                .containsAll(List.of(image, otherImage, attached, unattached)));
        // A conversation the caller does not own matches nothing, including their own upload used in it.
        assertEquals(List.of(), ids(library.page(scope, other, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), session.id()), LibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(), ids(library.page(scope, owner, JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), UUID.randomUUID()), LibraryFile.Sort.NEWEST, 0, 50)));
    }

    @Test
    void theLibraryRenamesStarsAndListsPendingUploadsOnlyForTheirOwner() {
        var library = new JdbcLibraryRepository(jdbc);
        var artifacts = new JdbcChatArtifactRepository(jdbc);
        var interpreter = new JdbcInterpreterRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Tệp");
        var reply = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Make a workbook");
        var upload = readyFile(owner);
        var failed = readyFile(owner);
        jdbc.sql("UPDATE chat_user_file SET status='FAILED', error_code='EXTRACTION_FAILED' WHERE id=:id").param("id", failed).update();
        var generated = UUID.randomUUID();
        interpreter.insertArtifact(scope, reply.assistantMessageId(), generated, UUID.randomUUID(),
                new ObjectKey("tenants/" + tenant + "/bang.xlsx"), "bang.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 64, null);
        var filter = JdbcLibraryRepository.Filter.of("", Set.of(), Set.of(), null);

        // The READY list is unchanged; the pending list holds only the failed upload, with its reason.
        assertEquals(List.of(generated, upload), ids(library.page(scope, owner, filter, LibraryFile.Sort.NEWEST, 0, 50)));
        var pending = library.page(scope, owner, new JdbcLibraryRepository.Filter("",
                Set.of(), Set.of(), null, false, true, null), LibraryFile.Sort.NEWEST, 0, 50);
        assertEquals(List.of(failed), ids(pending));
        assertEquals(UserFile.Status.FAILED, pending.items().getFirst().status());
        assertEquals("EXTRACTION_FAILED", pending.items().getFirst().errorCode());

        // A rename rewrites the file's own name, so the name search and the listing follow it.
        assertTrue(artifacts.update(scope, owner, LibraryFile.Source.GENERATED, generated, "Doanh thu quý 3.xlsx", null));
        assertTrue(library.update(scope, owner, upload, null, true));
        assertEquals(List.of(generated), ids(library.page(scope, owner,
                JdbcLibraryRepository.Filter.of("quý 3", Set.of(), Set.of(), null),
                LibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(generated, upload), ids(library.page(scope, owner, filter, LibraryFile.Sort.NAME, 0, 50)));
        var starred = library.page(scope, owner, new JdbcLibraryRepository.Filter("",
                Set.of(), Set.of(), null, true, false, null), LibraryFile.Sort.NEWEST, 0, 50);
        assertEquals(List.of(upload), ids(starred));
        assertTrue(starred.items().getFirst().favorite());
        // Unstarring clears it; another member can neither rename nor star the file.
        assertTrue(library.update(scope, owner, upload, null, false));
        assertEquals(List.of(), ids(library.page(scope, owner, new JdbcLibraryRepository.Filter("",
                Set.of(), Set.of(), null, true, false, null), LibraryFile.Sort.NEWEST, 0, 50)));
        assertFalse(artifacts.update(scope, other, LibraryFile.Source.GENERATED, generated, "x.xlsx", true));
        assertFalse(artifacts.update(scope, owner, LibraryFile.Source.IMAGE, generated, "x.png", null));
        // A deleted artifact is no longer the owner's to change either.
        jdbc.sql("UPDATE chat_file_artifact SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id").param("id", generated).update();
        assertFalse(artifacts.update(scope, owner, LibraryFile.Source.GENERATED, generated, null, true));
        // Only the listed ids remain when the filter names them.
        assertEquals(List.of(upload), ids(library.page(scope, owner, new JdbcLibraryRepository.Filter("",
                Set.of(), Set.of(), null, false, false, Set.of(upload)), LibraryFile.Sort.NEWEST, 0, 50)));
    }

    private static List<UUID> ids(JdbcLibraryRepository.Page page) {
        return page.items().stream().map(LibraryFile::id).toList();
    }

    @Test
    void automaticTitlesAreOnceOnlyAuthorizedAndManualRenameWinsEvenWithTheSameText() {
        var session = sessions.create(owner, "Short initial title");
        assertTrue(turns.claimTitle(owner, session.id()).isEmpty());
        var reply = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        assertTrue(turns.claimTitle(owner, session.id()).isEmpty());
        turns.finish(session.id(), reply.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer");
        assertThrows(ChatException.class, () -> turns.claimTitle(other, session.id()));
        var input = turns.claimTitle(owner, session.id()).orElseThrow();
        assertTrue(turns.claimTitle(owner, session.id()).isEmpty());
        sessions.rename(owner, session.id(), "Short initial title");
        turns.completeTitle(owner, input, "Generated title");
        assertEquals("Short initial title", sessions.get(owner, session.id()).title());

        var second = sessions.create(owner, "Fallback");
        var secondReply = reserve(second, second.rootMessageId(), UUID.randomUUID(), "Question");
        turns.finish(second.id(), secondReply.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer");
        var pending = turns.claimTitle(owner, second.id()).orElseThrow();
        turns.completeTitle(owner, pending, "Generated title");
        assertEquals("Generated title", sessions.get(owner, second.id()).title());
        assertTrue(turns.claimTitle(owner, second.id()).isEmpty());
    }

    @Test
    void accountLanguageIsCapturedAtAdmissionAndChangesOnlyForTheNextTurn() {
        var session = sessions.create(owner, "Language snapshot");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        assertNotNull(first.context());
        assertEquals("vi", first.context().uiLanguage());
        jdbc.sql("UPDATE actors SET ui_language = 'en' WHERE id = :actor").param("actor", owner.value()).update();
        assertEquals("vi", turns.loadContext(owner, session.id(), first).uiLanguage());
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer");
        var next = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Next question");
        assertNotNull(next.context());
        assertEquals("en", next.context().uiLanguage());
    }

    @Test
    void editAndRegenerateKeepOldBranchesAndCommandIdentityWithoutDuplicatingUserMessages() {
        var session = sessions.create(owner, "Versions");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "First question");
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "First answer");
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Follow-up");
        turns.finish(session.id(), second.assistantMessageId(), ChatMessage.Status.COMPLETED, "Second answer");
        var regenerate = ChatCommand.builder(ChatCommand.Operation.REGENERATE, first.userMessageId(), UUID.randomUUID(), "").build();
        var retryAnswer = turns.reserve(owner, session.id(), regenerate, Duration.ofMinutes(2), 32000, null);
        assertEquals(first.userMessageId(), retryAnswer.userMessageId());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM chat_message WHERE session_id=:session AND role='USER'")
                .param("session", session.id()).query(Long.class).single());
        assertThrows(ChatException.class, () -> sessions.selectBranch(owner, session.id(), first.assistantMessageId(), retryAnswer.assistantMessageId()));
        turns.finish(session.id(), retryAnswer.assistantMessageId(), ChatMessage.Status.COMPLETED, "Alternative");
        assertEquals(2, sessions.history(owner, session.id(), null, 100).size());
        sessions.selectBranch(owner, session.id(), first.assistantMessageId(), retryAnswer.assistantMessageId());
        assertEquals(4, sessions.history(owner, session.id(), null, 100).size());
        var edit = ChatCommand.builder(ChatCommand.Operation.EDIT, first.userMessageId(), UUID.randomUUID(), "Edited question").build();
        var edited = turns.reserve(owner, session.id(), edit, Duration.ofMinutes(2), 32000, null);
        assertNotEquals(first.userMessageId(), edited.userMessageId());
        assertEquals("Edited question", turns.loadContext(owner, session.id(), edited).newestFirst().getFirst().content());
        assertEquals(1, turns.loadContext(owner, session.id(), edited).newestFirst().size());
        turns.finish(session.id(), edited.assistantMessageId(), ChatMessage.Status.COMPLETED, "Edited answer");
        assertEquals(retryAnswer.assistantMessageId(), turns.reserve(owner, session.id(), regenerate, Duration.ofMinutes(2), 32000, null).assistantMessageId());
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(),
                ChatCommand.builder(ChatCommand.Operation.EDIT, first.userMessageId(), regenerate.requestId(),
                        "Other").build(), Duration.ofMinutes(2), 32000, null));
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
        var regeneration = turns.reserve(owner, session.id(), ChatCommand.builder(ChatCommand.Operation.REGENERATE,
                first.userMessageId(), UUID.randomUUID(), "").build(), Duration.ofMinutes(2), 32000, null);
        assertTrue(collaboration.feedback(owner, session.id(), List.of(regeneration.assistantMessageId())).isEmpty());
        collaboration.removeFeedback(owner, session.id(), first.assistantMessageId());
        collaboration.removeFeedback(owner, session.id(), first.assistantMessageId());
        collaboration.share(owner, session.id(), false, sharing.revision());
        assertThrows(ChatException.class, () -> collaboration.shared(other, session.id()));
        turns.delete(owner, session.id());
        assertFalse(turns.finish(session.id(), regeneration.assistantMessageId(), ChatMessage.Status.COMPLETED, "Late answer"));
        assertThrows(ChatException.class, () -> sessions.get(owner, session.id()));
        assertTrue(sessions.list(owner, false, 0, 100).isEmpty());
    }

    @Test
    void oversizedQuestionRollsBackBeforeTreeAdvancesAndBuiltinConfigurationSurvivesSend() {
        var session = sessions.create(owner, "Validation");
        var request = UUID.randomUUID();
        assertThrows(AiException.class, () -> turns.reserve(owner, session.id(), session.rootMessageId(), request,
                "Question ".repeat(500), Duration.ofMinutes(2), 100));
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        jdbc.sql("UPDATE persona SET instructions = 'Answer' WHERE id = :id").param("id", session.personaId()).update();
        var tokens = Tokenizers.o200k();
        var policy = ModelRequestPolicy.hosted(tokens, p -> p);
        var binding = ModelBinding.builder(new SpringAiLlmService("fixture", "fixture", Mockito.mock(ChatModel.class)),
                p -> p, policy, 32000, 4096, false, false).build();
        String contribution = "Current date: 2026-09-11\n";
        // Reservation validates and stores the resolved prompt, including the account-language block.
        String instructions = ChatTurnSetup.instructions(
                ChatPrompts.resolve("Answer", false, Instant.now(), "vi"),
                contribution);
        int raw = tokens.estimate(instructions) + tokens.estimate("Question");
        var selection = new ChatTurnPersistence.ModelSelection(null, UUID.randomUUID(), null, binding, null, contribution);
        assertThrows(AiException.class, () -> turns.reserve(owner, session.id(), session.rootMessageId(), request,
                "Question", Duration.ofMinutes(2), raw, selection));
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        var reservation = turns.reserve(owner, session.id(), session.rootMessageId(), request, "Question", Duration.ofMinutes(2), raw + 64, selection);
        var setup = ChatTurnSetup.resolve(session.id(), reservation.assistantMessageId(),
                turns.loadContext(owner, session.id(), reservation), raw + 64, binding, contribution);
        // The prompt carries the instant it was resolved at, which differs between this expectation and the reservation.
        assertEquals(List.of(undated(instructions), "Question"), setup.messages().stream().map(Message::getContent).map(ChatPersistenceIntegrationTest::undated).toList());
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
        var source = ChatSource.document(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 2, 2,
                List.of(new ChatSource.Provenance(2, "[{\"page\":3}]")));
        var artifact = new ChatArtifact(UUID.randomUUID(), "Allowance", "{\"root\":{\"component\":\"Metric\",\"props\":{\"label\":\"Days\",\"value\":\"12\"}}}");
        var activity = new ChatActivity(List.of(new ChatActivity.ActivityStep(1, "call_1", "search_knowledge", ChatActivity.StepStatus.COMPLETED,
                Instant.parse("2026-09-14T00:00:00Z"), 120L, 0, List.of("leave policy"), null, List.of(), List.of(1))),
                List.of(new ChatActivity.ReasoningSegment(0, 0, "Checking the HR policy.")));
        // No turn writes read-only UI artifacts any more (render_gui was removed); a stored one still reads back.
        jdbc.sql("UPDATE chat_message SET artifacts = CAST(:artifacts AS jsonb) WHERE id = :id")
                .param("artifacts", new ObjectMapper().writeValueAsString(List.of(artifact)))
                .param("id", pair.assistantMessageId()).update();
        turns.finishAndRead(session.id(), pair.assistantMessageId(), ChatMessage.Status.CANCELED,
                "Twelve days [1]", null, "model", 10L, 4L, null, List.of(source), activity);
        turns.finishAndRead(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "Late answer", null, "model", 20L, 5L, null, List.of());
        var saved = sessions.history(owner, session.id(), null, 100).getLast();
        assertEquals(ChatMessage.Status.CANCELED, saved.status());
        assertEquals("Twelve days [1]", saved.content());
        assertEquals(List.of(source), saved.sources());
        assertEquals(List.of(artifact), saved.artifacts());
        assertEquals(activity, saved.activity());
        var question = sessions.history(owner, session.id(), null, 100).getFirst();
        assertEquals(ChatActivity.EMPTY, question.activity());
        assertThrows(DataAccessException.class, () -> jdbc.sql(
                "UPDATE chat_message SET activity = CAST(:activity AS jsonb) WHERE id = :id")
                .param("activity", "{\"steps\": [], \"reasoning\": [{\"position\": 0, \"textOffset\": 0, \"text\": \"x\"}]}")
                .param("id", question.id()).update(), "Only assistant replies carry activity");
        // Source IDs intentionally need no live document FK: reindex/delete does not rewrite old answers.
        assertThrows(ChatException.class, () -> sessions.history(other, session.id(), null, 100));
    }

    @Test
    void expiredNullReplyDoesNotTruncateAncestorContext() {
        var session = sessions.create(owner, "Context after expiry");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Earlier question");
        jdbc.sql("UPDATE chat_message SET deadline_at = CURRENT_TIMESTAMP - INTERVAL '10 seconds' WHERE id = :id")
                .param("id", first.assistantMessageId()).update();
        reconciliation().endLapsed();
        assertEquals(ChatMessage.Status.FAILED, turns.authorizeReply(owner, session.id(), first.assistantMessageId()));
        var next = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Newest question");
        var context = turns.loadContext(owner, session.id(), next);
        assertEquals(List.of(next.userMessageId(), first.assistantMessageId(), first.userMessageId()),
                context.newestFirst().stream().map(ChatMessage::id).toList());
    }

    @Test
    void aSummaryStandsForItsBranchUntilAnEditLeavesItsParentOrCutoffBehind() {
        var summaries = new JdbcChatSummaryRepository(jdbc);
        var session = sessions.create(owner, "Compacted");
        var first = answered(session, session.rootMessageId(), "First question");
        var second = answered(session, first.assistantMessageId(), "Second question");
        summaries.insert(new TenantId(tenant), session.id(), second.userMessageId(), first.assistantMessageId(),
                "The person compares two plans.", 12, null);
        var third = reserve(session, second.assistantMessageId(), UUID.randomUUID(), "Third question");
        assertEquals(new ChatTurnPersistence.TurnContext.Summary(first.assistantMessageId(), "The person compares two plans."),
                Objects.requireNonNull(third.context()).summary());
        turns.finish(session.id(), third.assistantMessageId(), ChatMessage.Status.COMPLETED, "Third answer");

        // An edit above the cutoff starts a branch that holds neither the parent nor the cutoff.
        var aboveCutoff = turns.reserve(owner, session.id(), ChatCommand.builder(ChatCommand.Operation.EDIT,
                first.userMessageId(), UUID.randomUUID(), "First question, edited").build(), Duration.ofMinutes(2), 32000, null);
        assertNull(turns.loadContext(owner, session.id(), aboveCutoff).summary());
        turns.finish(session.id(), aboveCutoff.assistantMessageId(), ChatMessage.Status.COMPLETED, "Edited answer");
        sessions.selectBranch(owner, session.id(), first.userMessageId(), aboveCutoff.userMessageId());

        // Editing the summary's own question keeps the cutoff on the branch but not the parent: another branch.
        var sibling = turns.reserve(owner, session.id(), ChatCommand.builder(ChatCommand.Operation.EDIT,
                second.userMessageId(), UUID.randomUUID(), "Second question, edited").build(), Duration.ofMinutes(2), 32000, null);
        assertNull(turns.loadContext(owner, session.id(), sibling).summary());
    }

    @Test
    void theNearestSummaryOnTheBranchWinsOverANewerOneFurtherUp() {
        var summaries = new JdbcChatSummaryRepository(jdbc);
        var session = sessions.create(owner, "Compacted twice");
        var first = answered(session, session.rootMessageId(), "First question");
        var second = answered(session, first.assistantMessageId(), "Second question");
        var third = answered(session, second.assistantMessageId(), "Third question");
        summaries.insert(new TenantId(tenant), session.id(), third.userMessageId(), second.assistantMessageId(),
                "Rolled forward", 8, null);
        summaries.insert(new TenantId(tenant), session.id(), second.userMessageId(), first.assistantMessageId(),
                "First summary", 8, null);
        var fourth = reserve(session, third.assistantMessageId(), UUID.randomUUID(), "Fourth question");
        var summary = Objects.requireNonNull(turns.loadContext(owner, session.id(), fourth).summary());
        assertEquals("Rolled forward", summary.content());
        assertEquals(second.assistantMessageId(), summary.cutoffMessageId());
    }

    @Test
    void oneCompactionHoldsASessionAndAFailureMakesItWait() {
        var session = sessions.create(owner, "Claimed");
        var first = answered(session, session.rootMessageId(), "First question");
        var id = new TenantId(tenant);
        assertTrue(turns.claimCompaction(id, session.id(), Duration.ofMinutes(5)));
        assertFalse(turns.claimCompaction(id, session.id(), Duration.ofMinutes(5)), "a second compaction leaves it");
        turns.releaseCompaction(session.id(), null);
        assertTrue(turns.claimCompaction(id, session.id(), Duration.ofMinutes(5)), "a released claim is free");
        turns.releaseCompaction(session.id(), Duration.ofMinutes(10));
        assertFalse(turns.claimCompaction(id, session.id(), Duration.ofMinutes(5)), "a failure waits its cooldown");
        jdbc.sql("UPDATE chat_session_compaction SET retry_after = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE session_id = :session")
                .param("session", session.id()).update();
        assertTrue(turns.claimCompaction(id, session.id(), Duration.ofMinutes(5)), "past its cooldown it is tried again");
        jdbc.sql("UPDATE chat_session_compaction SET claimed_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE session_id = :session")
                .param("session", session.id()).update();
        assertTrue(turns.claimCompaction(id, session.id(), Duration.ofMinutes(5)), "a claim whose holder died lapses");

        // The compaction reads the branch from the answer back, and saves a summary the next turn replays.
        var input = turns.compactionInput(owner, session.id(), first.assistantMessageId()).orElseThrow();
        assertEquals(List.of(first.assistantMessageId(), first.userMessageId()),
                input.newestFirst().stream().map(ChatMessage::id).toList());
        assertNull(input.summary());
        turns.saveSummary(id, session.id(), first.userMessageId(), first.userMessageId(), "The person asked once.", 6, null);
        var next = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Second question");
        assertEquals("The person asked once.", Objects.requireNonNull(Objects.requireNonNull(next.context()).summary()).content());
    }

    @Test
    void deletingTheSessionDeletesItsSummariesAndCompactionState() {
        var session = sessions.create(owner, "Deleted");
        var first = answered(session, session.rootMessageId(), "First question");
        new JdbcChatSummaryRepository(jdbc).insert(new TenantId(tenant), session.id(), first.userMessageId(),
                first.userMessageId(), "Summary", 4, null);
        jdbc.sql("INSERT INTO chat_session_compaction(session_id, tenant_id, retry_after) VALUES (:session, :tenant, CURRENT_TIMESTAMP)")
                .param("session", session.id()).param("tenant", tenant).update();
        jdbc.sql("DELETE FROM chat_session WHERE id = :session").param("session", session.id()).update();
        assertEquals(0L, jdbc.sql("SELECT (SELECT count(*) FROM chat_session_summary) + (SELECT count(*) FROM chat_session_compaction)")
                .query(Long.class).single());
    }

    private ChatTurnPersistence.Reservation answered(ChatSession session, UUID parent, String question) {
        var reserved = reserve(session, parent, UUID.randomUUID(), question);
        turns.finish(session.id(), reserved.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer to " + question);
        return reserved;
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
        assertEquals(1, reconciliation().endLapsed());
        assertEquals(ChatMessage.Status.FAILED, sessions.history(owner, session.id(), null, 20).getLast().status());
        assertFalse(turns.finish(session.id(), reserved.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "late", null, null, null, null, null));
    }

    @Test
    void answerStoresMoreThanTwentyFourSourcesAndTheColumnKeepsAByteBound() {
        var session = sessions.create(owner, "Many sources");
        var pair = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        var sources = IntStream.rangeClosed(1, 30)
                .mapToObj(index -> ChatSource.file(index, UUID.randomUUID(), "File " + index, null, null)).toList();
        assertTrue(new JdbcChatRepository(jdbc).finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "Answer [30]", null, null, null, null, null, sources));
        assertEquals(30, sessions.history(owner, session.id(), null, 20).getLast().sources().size());
        assertThrows(DataAccessException.class, () -> jdbc.sql(
                "UPDATE chat_message SET sources = jsonb_build_array(repeat('x', 1048576)) WHERE id = :id")
                .param("id", pair.assistantMessageId()).update(), "Stored sources stay bounded to 1 MiB");
    }

    @Test
    void researchClarificationAndPlanAreStoredWithTheTerminalOutcomeAndBounded() {
        var session = sessions.create(owner, "Research state");
        var pair = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        var agent = new ChatResearch.Agent("call_revenue", 0, 1, "Revenue in 2025", ChatActivity.StepStatus.COMPLETED, 900L,
                "Revenue grew [1].", List.of(new ChatResearchEvent.Citation(1, 4)), new ChatActivity(List.of(new ChatActivity.ActivityStep(0,
                "call_search", "search_knowledge", ChatActivity.StepStatus.COMPLETED, Instant.parse("2026-09-15T10:00:00Z"), 12L, 0,
                List.of("revenue"), null, List.of(), List.of())), List.of()));
        var state = new ChatResearch(true, "1. Revenue", List.of(agent));
        assertTrue(new JdbcChatRepository(jdbc).finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "Which fiscal year?", null, null, null, null, null, List.of(), ChatActivity.EMPTY, state));
        var history = sessions.history(owner, session.id(), null, 20);
        assertEquals(state, history.getLast().research());
        assertThrows(DataAccessException.class, () -> jdbc.sql(
                "UPDATE chat_message SET research_agents = '[{}]'::jsonb WHERE id = :id").param("id", history.getFirst().id()).update(),
                "Only assistant messages carry research agents");
        assertEquals(ChatResearch.EMPTY, history.getFirst().research(), "Ordinary messages carry no research state");
        assertThrows(DataAccessException.class, () -> jdbc.sql(
                "UPDATE chat_message SET research_plan = repeat('x', 100001) WHERE id = :id")
                .param("id", pair.assistantMessageId()).update(), "The stored plan stays bounded");
        assertThrows(IllegalArgumentException.class, () -> new ChatResearch(false, "x".repeat(ChatResearch.MAX_PLAN + 1), List.of()));
    }

    @Test
    void deepResearchModeIsPartOfCommandIdentity() {
        var session = sessions.create(owner, "Research identity");
        var request = UUID.randomUUID();
        var research = ChatCommand.builder(ChatCommand.Operation.SEND, session.rootMessageId(), request, "Question")
                .deepResearch(true)
                .build();
        var reserved = turns.reserve(owner, session.id(), research, Duration.ofMinutes(30), 32000, null);
        assertTrue(reserved.created());
        assertTrue(jdbc.sql("SELECT deep_research FROM chat_command WHERE session_id = :session AND request_id = :request")
                .param("session", session.id()).param("request", request).query(Boolean.class).single());
        var replay = turns.reserve(owner, session.id(), research, Duration.ofMinutes(30), 32000, null);
        assertEquals(reserved.assistantMessageId(), replay.assistantMessageId());
        var ordinary = ChatCommand.builder(ChatCommand.Operation.SEND, session.rootMessageId(), request, "Question").build();
        assertEquals("CHAT_CONFLICT", assertThrows(ChatException.class,
                () -> turns.reserve(owner, session.id(), ordinary, Duration.ofMinutes(30), 32000, null)).code());
    }

    @Test
    void createsPrivateSessionWithOneRootAndSharedDefaultPersona() {
        var first = sessions.create(owner, "First");
        var second = sessions.create(owner, "Second");
        assertEquals(first.personaId(), second.personaId());
        assertEquals(first, sessions.get(owner, first.id()));
        assertTrue(sessions.history(owner, first.id(), null, 20).isEmpty());
        assertTrue(sessions.list(owner, false, 0, 30).isEmpty(), "a conversation nobody asked anything in is no topic");
        ask(owner, first, "Câu hỏi đầu tiên?", "Câu trả lời.");
        ask(owner, second, "Câu hỏi thứ hai?", "Câu trả lời.");
        assertEquals(2, sessions.list(owner, false, 0, 30).size());
        assertTrue(sessions.list(other, false, 0, 30).isEmpty());
        assertEquals("CHAT_UNAVAILABLE", assertThrows(ChatException.class, () -> sessions.get(other, first.id())).code());
        // The deployment schema permits one Tenant; verify the repository still scopes by its ID.
        assertTrue(new JdbcChatRepository(jdbc).findOwned(new TenantId(UUID.randomUUID()),
                owner, first.id(), false).isEmpty());
        jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor")
                .param("actor", owner.value()).update();
        assertThrows(ChatException.class, () -> sessions.get(owner, first.id()));
        assertThrows(ChatException.class, () -> sessions.create(owner, "Denied"));
    }

    @Test
    void aChannelConversationOpensForItsOwnerAndStaysOutOfTheSidebarAndSearch() {
        var web = sessions.create(owner, "Doanh thu quý trên web");
        ask(owner, web, "Doanh thu quý 3?", "Tăng 12%.");
        var channel = sessions.createInChannel(owner, "Doanh thu quý từ Lark");

        assertEquals(channel, sessions.get(owner, channel.id()), "its owner opens it by its address");
        assertEquals(List.of(web.id()), sessions.list(owner, false, 0, 30).stream().map(ChatSession::id).toList());
        assertEquals(List.of(web.id()), sessions.search(owner, "doanh thu", 0, 30).stream()
                .map(match -> match.session().id()).toList());
        assertEquals("CHAT_UNAVAILABLE", assertThrows(ChatException.class, () -> sessions.get(other, channel.id())).code());
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
    void lapsedButUnreconciledLeaseStillAcceptsTheLiveOutcome() {
        var session = sessions.create(owner, "Lapsed lease");
        var pair = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        // A slow renewal is not a failure: only reconciliation ends the row, and a live finish proves the process is alive.
        jdbc.sql("UPDATE chat_message SET deadline_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id")
                .param("id", pair.assistantMessageId()).update();
        assertTrue(turns.finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer"));
        assertEquals(ChatMessage.Status.COMPLETED, sessions.history(owner, session.id(), null, 20).getLast().status());
        assertFalse(turns.finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED, "Late"));
    }

    @Test
    void leaseRenewalKeepsRunningRowsAndReconciliationFailsOnlyLapsedLeases() {
        var session = sessions.create(owner, "Renewed lease");
        var live = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        var other = sessions.create(owner, "Lapsed lease");
        var lapsed = reserve(other, other.rootMessageId(), UUID.randomUUID(), "Question");
        jdbc.sql("UPDATE chat_message SET deadline_at = clock_timestamp() + interval '2 seconds' WHERE id = :id")
                .param("id", live.assistantMessageId()).update();
        jdbc.sql("UPDATE chat_message SET deadline_at = clock_timestamp() - interval '10 seconds' WHERE id = :id")
                .param("id", lapsed.assistantMessageId()).update();
        assertEquals(Set.of(live.assistantMessageId(), lapsed.assistantMessageId()),
                turns.renewLeases(List.of(live.assistantMessageId(), lapsed.assistantMessageId()), Duration.ofMinutes(30)));
        assertEquals(0, reconciliation().endLapsed(), "Renewed leases are not reconciled");
        jdbc.sql("UPDATE chat_message SET deadline_at = clock_timestamp() - interval '10 seconds' WHERE id = :id")
                .param("id", lapsed.assistantMessageId()).update();
        assertEquals(1, reconciliation().endLapsed());
        assertEquals(ChatMessage.Status.RUNNING, turns.authorizeReply(owner, session.id(), live.assistantMessageId()));
        assertEquals(ChatMessage.Status.FAILED, turns.authorizeReply(owner, other.id(), lapsed.assistantMessageId()));
        // A reconciled row is not renewed, so its process learns it no longer owns the run.
        assertEquals(Set.of(live.assistantMessageId()),
                turns.renewLeases(List.of(live.assistantMessageId(), lapsed.assistantMessageId()), Duration.ofMinutes(30)));
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
                var snapshot = repository.persona(session.id(), true, false);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                assertEquals(snapshot, repository.persona(session.id(), true, false));
                return snapshot;
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var writer = executor.submit(() -> personas.update(owner, before.id(), before.revision(),
                        input(before.name(), "Updated builtin", List.of(), List.of(sourceId), false, null)));
                try {
                    assertThrows(TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
                } finally {
                    release.countDown();
                }
                writer.get(10, TimeUnit.SECONDS);
                var snapshot = reader.get(10, TimeUnit.SECONDS);
                var after = tx.execute(_ -> repository.persona(session.id(), true, false));
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
                new IamLockRepository(jdbc).lockTenant(new TenantId(tenant));
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
                assertThrows(TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            revoke.get(10, TimeUnit.SECONDS);
            var denied = assertThrows(ExecutionException.class, () -> writer.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ChatException.class, denied.getCause());
        }
    }

    @Test
    void agentSharingFollowsOnyxUseEditPublicManagerAndTransferRules() {
        var creator = member(tenant); var viewer = member(tenant); var groupEditor = member(tenant);
        var stranger = member(tenant); var manager = member(tenant);
        for (var actor : List.of(viewer, groupEditor, stranger, manager))
            when(authorization.effectiveCapabilities(actor)).thenReturn(Set.of(IamCapability.CHAT_READ, IamCapability.CHAT_WRITE));
        when(authorization.effectiveCapabilities(creator)).thenReturn(Set.of(IamCapability.CHAT_WRITE, IamCapability.AGENTS_CREATE));
        assertThrows(ChatException.class, () -> personas.create(stranger, input("Denied", List.of(), List.of(), true, List.of())));
        var agent = personas.create(creator, input("KPI", List.of("Xếp loại tháng 8"), List.of(sourceId), true, List.of()));
        assertTrue(agent.permissions().edit()); assertTrue(agent.permissions().setPublic()); assertTrue(agent.permissions().delete());
        assertThrows(ChatException.class, () -> personas.get(stranger, agent.id()));

        UUID editors = group("Ban điều hành", Map.of(groupEditor, false, manager, true));
        var shared = personas.share(creator, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(
                List.of(new ChatPersonaService.UserShareInput(viewer.value(), AgentPermission.VIEWER)),
                List.of(new ChatPersonaService.GroupShareInput(editors, AgentPermission.EDITOR)), null, null));
        assertTrue(shared.revision() > agent.revision(), "Relation-only changes advance the revision");
        assertThrows(ChatException.class, () -> personas.share(creator, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(List.of(), List.of(), null, null)));

        var viewed = personas.get(viewer, agent.id());
        assertFalse(viewed.permissions().edit()); assertTrue(viewed.permissions().leave());
        assertEquals(List.of(sourceId), viewed.sourceIds(), "Viewers receive the full Onyx snapshot");
        assertEquals(1, viewed.groupShares().size());
        assertThrows(ChatException.class, () -> personas.update(viewer, agent.id(), shared.revision(),
                input("Viewer edit", List.of(), List.of(), true, null)));

        var edited = personas.update(groupEditor, agent.id(), shared.revision(), input("KPI tháng", List.of(), List.of(sourceId), true, null));
        assertEquals("KPI tháng", edited.name());
        // A non-owner editor cannot change Tenant-wide visibility; the rest of the share replacement applies.
        var editorShare = personas.share(groupEditor, agent.id(), edited.revision(), new ChatPersonaService.SharingInput(
                List.of(), List.of(new ChatPersonaService.GroupShareInput(editors, AgentPermission.EDITOR)), true,
                AgentPermission.EDITOR));
        assertFalse(editorShare.isPublic());
        assertThrows(ChatException.class, () -> personas.get(viewer, agent.id()));
        // The Group manager edits a private agent whose share Groups they all manage.
        assertTrue(personas.get(manager, agent.id()).permissions().edit());

        var published = personas.share(creator, agent.id(), editorShare.revision(), new ChatPersonaService.SharingInput(
                List.of(), List.of(new ChatPersonaService.GroupShareInput(editors, AgentPermission.VIEWER)), true,
                AgentPermission.VIEWER));
        assertTrue(published.isPublic());
        assertFalse(personas.get(stranger, agent.id()).permissions().edit());
        assertFalse(personas.get(manager, agent.id()).permissions().edit(), "Managers only edit private agents");
        var strangerSession = sessions.create(stranger, "Public agent");
        personas.select(stranger, strangerSession.id(), agent.id());
        assertEquals(List.of(sourceId), tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(strangerSession.id(), false, false)).options().sourceIds());
        assertTrue(personas.list(stranger, AgentListFilter.SHARED, null, "kpi", 0, 30).stream().anyMatch(view -> view.id().equals(agent.id())));

        var privateAgain = personas.share(creator, agent.id(), published.revision(), new ChatPersonaService.SharingInput(List.of(), List.of(), false, null));
        assertThrows(ChatException.class, () -> tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(strangerSession.id(), false, false)),
                "Revoked use fails the next admission");

        var transferred = personas.transfer(creator, agent.id(), privateAgain.revision(), new ChatPersonaService.TransferInput(viewer.value(), null));
        assertEquals(viewer.value(), transferred.owner().actor().actorId());
        assertTrue(transferred.userShares().stream().anyMatch(share -> share.person().actorId().equals(creator.value())
                && share.permission() == AgentPermission.EDITOR), "The previous owner keeps editing access");
        assertTrue(personas.get(creator, agent.id()).permissions().edit());
        assertFalse(personas.get(creator, agent.id()).permissions().delete());

        jdbc.sql("UPDATE tenant_memberships SET status='INACTIVE' WHERE tenant_id=:tenant AND actor_id=:actor")
                .param("tenant", tenant).param("actor", viewer.value()).update();
        var vacant = personas.get(owner, agent.id());
        assertTrue(vacant.vacant());
        assertTrue(vacant.permissions().transfer(), "Agent managers transfer vacant agents");
        var adopted = personas.transfer(owner, agent.id(), vacant.revision(), new ChatPersonaService.TransferInput(null, editors));
        assertEquals(editors, adopted.owner().group().id());
        // A direct sharee outside the owner Group sees a Group-owned agent under Shared.
        assertTrue(personas.list(creator, AgentListFilter.SHARED, null, null, 0, 30).stream()
                .anyMatch(view -> view.id().equals(agent.id())));
        assertTrue(personas.get(groupEditor, agent.id()).permissions().delete(), "AgentOwner Group members own the agent");
        personas.delete(groupEditor, agent.id(), personas.get(groupEditor, agent.id()).revision());
        assertThrows(ChatException.class, () -> personas.get(groupEditor, agent.id()));
        assertTrue(personas.restore(owner, agent.id()).deletedAt() == null);
    }

    @Test
    void anAgentGrantsItsFilesToThoseWhoUseItAndIsNamedAsTheirHolder() {
        var creator = member(tenant); var viewer = member(tenant); var stranger = member(tenant);
        when(authorization.effectiveCapabilities(creator)).thenReturn(Set.of(IamCapability.CHAT_WRITE, IamCapability.AGENTS_CREATE));
        var attached = readyFile(creator); var avatar = readyFile(creator); var unrelated = readyFile(creator);
        var agent = personas.create(creator, input("Tài liệu", List.of(), List.of(), false, List.of(attached)));
        // The avatar is set by row: the service only takes an image, and what is tested here is the grant.
        jdbc.sql("UPDATE persona SET avatar_file_id = :file WHERE id = :id").param("file", avatar).param("id", agent.id()).update();
        personas.share(creator, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(
                List.of(new ChatPersonaService.UserShareInput(viewer.value(), AgentPermission.VIEWER)), List.of(), null, null));
        var attachments = new JdbcChatFileAttachmentRepository(jdbc);
        var scope = new TenantId(tenant);
        var all = List.of(attached, avatar, unrelated);

        // Someone the agent is shared with reads its file and its avatar, not the creator's other uploads.
        var granted = attachments.usableThroughAgents(scope, viewer, all);
        assertEquals(Set.of(attached, avatar), granted);
        assertTrue(new JdbcUserFileRepository(jdbc).readable(scope, viewer, attached, granted, false).isPresent());
        assertTrue(new JdbcUserFileRepository(jdbc).readable(scope, viewer, unrelated, granted, false).isEmpty());
        // Someone it is not shared with is granted nothing.
        assertEquals(Set.of(), attachments.usableThroughAgents(scope, stranger, all));

        // The agent is what holds both files, once each; the unrelated upload is held by nothing.
        var holders = attachments.holders(scope, all);
        assertEquals(List.of(attached, avatar), holders.stream().map(FileAttachments.Holder::fileId).sorted(
                Comparator.comparing(all::indexOf)).toList());
        assertTrue(holders.stream().allMatch(holder -> holder.id().equals(agent.id())
                && holder.kind() == LibraryFile.Usage.Kind.AGENT && holder.name().equals("Tài liệu")));
    }

    @Test
    void anAssistantsTopicsAreItsConversationsFilteredAndSearchedWithTheirRunningReply() {
        var finance = personas.create(owner, input("Báo cáo tài chính", List.of(), List.of(), false, List.of()));
        var plain = sessions.create(owner, "Quy trình mua sắm");
        ask(owner, plain, "Quy trình mua sắm dưới 50 triệu?", "Cần 3 báo giá.");
        var older = sessions.create(owner, "Lợi nhuận gộp quý 3");
        personas.select(owner, older.id(), finance.id());
        ask(owner, older, "Lợi nhuận gộp quý 3?", "412,6 tỷ đồng.");
        var newer = sessions.create(owner, "Báo cáo tuần 41");
        personas.select(owner, newer.id(), finance.id());
        var reply = reserve(newer, newer.rootMessageId(), UUID.randomUUID(), "Doanh thu tuần 41?");
        var empty = sessions.create(owner, "Báo cáo tài chính");
        personas.select(owner, empty.id(), finance.id());

        assertEquals(List.of(newer.id(), older.id()), sessions.list(owner, false, finance.id(), 0, 30).stream()
                .map(ChatSession::id).toList());
        assertEquals(List.of(plain.id()), sessions.list(owner, false, plain.personaId(), 0, 30).stream()
                .map(ChatSession::id).toList(), "a plain conversation is a topic of the built-in assistant");
        assertTrue(sessions.list(other, false, finance.id(), 0, 30).isEmpty(), "only the caller's own topics");
        assertEquals(List.of(newer.id(), older.id()), sessions.search(owner, "", finance.id(), 0, 30).stream()
                .map(match -> match.session().id()).toList());
        assertEquals(List.of(older.id()), sessions.search(owner, "quý", finance.id(), 0, 30).stream()
                .map(match -> match.session().id()).toList());
        assertTrue(sessions.search(owner, "mua sắm", finance.id(), 0, 30).isEmpty());
        assertEquals(List.of(newer.id()), sessions.search(owner, "báo cáo", finance.id(), 0, 30).stream()
                .map(match -> match.session().id()).toList(), "a conversation nobody asked anything in is no topic");
        assertTrue(sessions.list(owner, false, null, 0, 30).stream().noneMatch(session -> session.id().equals(empty.id())));
        assertEquals(empty.id(), sessions.get(owner, empty.id()).id(), "it still opens from its address");

        assertTrue(sessions.get(owner, newer.id()).replyRunning());
        assertEquals(List.of(true, false), sessions.list(owner, false, finance.id(), 0, 30).stream()
                .map(ChatSession::replyRunning).toList());
        assertTrue(sessions.search(owner, "tuần", finance.id(), 0, 30).getFirst().session().replyRunning());
        turns.finish(newer.id(), reply.assistantMessageId(), ChatMessage.Status.COMPLETED, "Tăng 6,2%.");
        assertFalse(sessions.get(owner, newer.id()).replyRunning());
    }

    @Test
    void theListOfAssistantsLeadsWithTheBuiltinOneAndKeepsAnUnusablePinWithItsTopics() {
        var creator = member(tenant); var reader = member(tenant);
        when(authorization.effectiveCapabilities(creator)).thenReturn(Set.of(IamCapability.CHAT_WRITE, IamCapability.AGENTS_CREATE));
        when(authorization.effectiveCapabilities(reader)).thenReturn(Set.of(IamCapability.CHAT_READ, IamCapability.CHAT_WRITE));
        var finance = published(creator, personas.create(creator, input("Báo cáo tài chính", List.of(), List.of(), false, List.of())));
        var hr = published(creator, personas.create(creator, new ChatPersonaService.PersonaInput("Chính sách nhân sự",
                null, "Nghỉ phép, lương thưởng và công tác phí", "", null, List.of(), List.of(), null, Set.of(), null, null,
                null, List.of(), "hr-face", null, null, null, null)));
        personas.replacePins(reader, List.of(hr.id(), finance.id()));
        var plain = sessions.create(reader, "Quy trình mua sắm");
        ask(reader, plain, "Quy trình mua sắm dưới 50 triệu?", "Cần 3 báo giá.");
        var hrTopic = sessions.create(reader, "Ngày phép còn lại");
        personas.select(reader, hrTopic.id(), hr.id());
        ask(reader, hrTopic, "Tôi còn bao nhiêu ngày phép?", "Còn 7 ngày.");
        var older = sessions.create(reader, "Lợi nhuận gộp quý 3");
        personas.select(reader, older.id(), finance.id());
        ask(reader, older, "Lợi nhuận gộp quý 3?", "412,6 tỷ đồng.");
        var newer = sessions.create(reader, "Báo cáo tuần 41");
        personas.select(reader, newer.id(), finance.id());
        var reply = turns.reserve(reader, newer.id(), newer.rootMessageId(), UUID.randomUUID(), "Doanh thu tuần 41?",
                Duration.ofMinutes(2), 32000);
        var empty = sessions.create(reader, "Báo cáo tài chính");
        personas.select(reader, empty.id(), finance.id());

        statements.reset();
        var assistants = personas.assistants(reader);
        long reads = statements.statements().size();
        assertEquals(List.of(plain.personaId(), hr.id(), finance.id()), assistants.stream().map(ChatAssistant::id).toList(),
                "the built-in assistant first, then the pins in order");
        var builtin = assistants.getFirst();
        assertTrue(builtin.builtin() && builtin.usable());
        assertNotNull(builtin.latestTopic());
        assertEquals(plain.id(), builtin.latestTopic().sessionId());
        assertEquals("Cần 3 báo giá.", builtin.latestTopic().preview());
        var financeRow = assistants.get(2);
        assertNotNull(financeRow.latestTopic());
        assertEquals(newer.id(), financeRow.latestTopic().sessionId(), "a newer conversation nobody asked anything in is skipped");
        assertTrue(financeRow.latestTopic().replyRunning());
        assertEquals("Doanh thu tuần 41?", financeRow.latestTopic().preview());

        turns.finish(newer.id(), reply.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "**Doanh thu** tăng 6,2% [1] so với [tuần trước](https://example.com/w40).\n\n```\nraw\n```");
        var finished = personas.assistants(reader).get(2).latestTopic();
        assertNotNull(finished);
        assertFalse(finished.replyRunning());
        assertEquals("Doanh thu tăng 6,2% so với tuần trước.", finished.preview());

        var third = published(creator, personas.create(creator, input("OKR/KPI hằng tháng", List.of(), List.of(), false, List.of())));
        personas.replacePins(reader, List.of(hr.id(), finance.id(), third.id()));
        var kpi = sessions.create(reader, "Xếp loại C tháng 9");
        personas.select(reader, kpi.id(), third.id());
        ask(reader, kpi, "Chi nhánh nào xếp loại C tháng 9?", "Hải Phòng, Vinh và Cần Thơ.");
        statements.reset();
        assertEquals(4, personas.assistants(reader).size());
        assertEquals(reads, statements.statements().size(), "one more pin reads no more statements");

        var hrNow = personas.get(creator, hr.id());
        personas.share(creator, hr.id(), hrNow.revision(), new ChatPersonaService.SharingInput(List.of(), List.of(), false, null));
        var unusable = personas.assistants(reader).get(1);
        assertEquals(hr.id(), unusable.id());
        assertFalse(unusable.usable());
        assertEquals("Chính sách nhân sự", unusable.name());
        assertEquals("hr-face", unusable.avatarSeed());
        assertEquals("", unusable.description(), "an unusable assistant shows only its name and face");
        assertNotNull(unusable.latestTopic());
        assertEquals(hrTopic.id(), unusable.latestTopic().sessionId());
        assertEquals(List.of(hrTopic.id()), sessions.list(reader, false, hr.id(), 0, 30).stream().map(ChatSession::id).toList(),
                "its topics stay listed");

        personas.replacePins(reader, List.of(finance.id(), third.id(), hr.id()));
        assertEquals(List.of(plain.personaId(), finance.id(), third.id(), hr.id()),
                personas.assistants(reader).stream().map(ChatAssistant::id).toList(), "a reorder keeps the unusable pin");
        assertEquals(List.of(finance.id(), third.id()), personas.pins(reader).stream().map(ChatPersonaService.PersonaView::id).toList());
        personas.replacePins(reader, List.of(finance.id()));
        personas.replacePins(reader, List.of(finance.id(), hr.id()));
        assertEquals(List.of(plain.personaId(), finance.id()), personas.assistants(reader).stream().map(ChatAssistant::id).toList(),
                "an unusable assistant cannot be pinned again");
    }

    private ChatPersonaService.PersonaView published(ActorId creator, ChatPersonaService.PersonaView agent) {
        return personas.share(creator, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(List.of(), List.of(), true, null));
    }

    @Test
    void featuredPublicAgentsSeedPinsOnceAndLabelsAreManaged() {
        var creator = member(tenant); var reader = member(tenant);
        when(authorization.effectiveCapabilities(creator)).thenReturn(Set.of(IamCapability.CHAT_WRITE, IamCapability.AGENTS_CREATE));
        when(authorization.effectiveCapabilities(reader)).thenReturn(Set.of(IamCapability.CHAT_READ, IamCapability.CHAT_WRITE));
        var label = personas.createLabel(reader, "Tài chính");
        assertThrows(ChatException.class, () -> personas.createLabel(creator, "tài chính"));
        assertThrows(ChatException.class, () -> personas.renameLabel(reader, label.id(), "Finance"));
        var agent = personas.create(creator, new ChatPersonaService.PersonaInput("Finance", null, "", "", null,
                List.of(), List.of(), null, Set.of("search"), null, null, null, List.of(), "kt-finance", null, null,
                List.of(label.id()), null));
        assertEquals(List.of(label), agent.labels());
        var published = personas.share(creator, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(List.of(), List.of(), true, null));
        assertThrows(ChatException.class, () -> personas.listing(creator, agent.id(), published.revision(), new ChatPersonaService.ListingInput(true, true, 1)));
        personas.listing(owner, agent.id(), published.revision(), new ChatPersonaService.ListingInput(true, true, 1));
        assertEquals(List.of(agent.id()), personas.pins(reader).stream().map(ChatPersonaService.PersonaView::id).toList());
        personas.replacePins(reader, List.of());
        assertTrue(personas.pins(reader).isEmpty(), "Seeding runs once per Actor");
        assertThrows(ChatException.class, () -> personas.reorder(creator, List.of(agent.id())));
        assertThrows(ChatException.class, () -> personas.reorder(owner, List.of(agent.id(), agent.id())));
        personas.reorder(owner, List.of(agent.id()));
        assertEquals(0, personas.get(owner, agent.id()).displayPriority());

        var session = sessions.create(reader, "Finance chat");
        personas.select(reader, session.id(), agent.id());
        var settings = tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(session.id(), false, false));
        assertEquals(Set.of("search"), settings.tools());
        assertFalse(settings.options().codeInterpreter(), "run_python follows the agent tool policy");
        assertEquals(List.of(), settings.mcpServerIds());
    }

    @Test
    void anAgentKeepsItsTitleGreetingAndReasoningLevelAndANewOneSearchesDocumentsAndTheWeb() {
        var agent = personas.create(owner, new ChatPersonaService.PersonaInput("Kế toán", "  Chuyên viên kế toán nội bộ  ",
                "", "Trả lời từ sổ sách.", "Xin chào, mình giúp bạn tra cứu chứng từ và hạn nộp thuế.", List.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null, null));
        assertEquals("Chuyên viên kế toán nội bộ", agent.title());
        assertEquals("Xin chào, mình giúp bạn tra cứu chứng từ và hạn nộp thuế.", agent.greeting());
        assertNull(agent.reasoningEffort());
        assertEquals(Set.of("search", "web_search"), agent.tools(), "a new agent searches documents and the web only");

        // The default model in this harness does not reason, so a level is refused until a reasoning model is offered.
        var level = new ChatPersonaService.PersonaInput("Kế toán", "Chuyên viên kế toán nội bộ", "", "Trả lời từ sổ sách.",
                "", List.of(), List.of(), null, null, null, null, ReasoningEffort.HIGH, null, null, null, null, null, null);
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class,
                () -> personas.update(owner, agent.id(), agent.revision(), level)).code());
        when(models.availableModelsForPersona(any(), any())).thenReturn(List.of(new ModelCatalogService.AvailableModel(
                UUID.randomUUID(), UUID.randomUUID(), "Provider", "model", "Model",
                new ModelSettings.Capabilities(true, true, false, true, false), 32000, 4096, null, true)));
        var thinking = personas.update(owner, agent.id(), agent.revision(), level);
        assertEquals(ReasoningEffort.HIGH, thinking.reasoningEffort());
        assertNull(thinking.greeting(), "a blank greeting clears it");
        assertEquals(Set.of("search", "web_search"), thinking.tools(), "omitted tools keep their value on update");
        assertThrows(ChatException.class, () -> personas.update(owner, agent.id(), thinking.revision(),
                new ChatPersonaService.PersonaInput("Kế toán", "t".repeat(81), "", "", null, List.of(), List.of(), null, null,
                        null, null, null, null, null, null, null, null, null)));

        var session = sessions.create(owner, "Hạn nộp thuế");
        personas.select(owner, session.id(), agent.id());
        var settings = tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(session.id(), false, false));
        assertEquals(ReasoningEffort.HIGH, settings.reasoningEffort(), "the agent's level applies to its conversations");
        sessions.pinReasoningEffort(owner, session.id(), ReasoningEffort.LOW);
        assertEquals(ReasoningEffort.LOW, tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(session.id(), false, false))
                .reasoningEffort(), "a level pinned on the conversation outranks the agent's");
    }

    @Test
    void reorderLocksEveryAgentInOneStatement() {
        var ids = new ArrayList<UUID>();
        for (int index = 0; index < 5; index++)
            ids.add(personas.create(owner, input("Agent " + index, List.of(), List.of(), false, List.of())).id());
        var ordered = new ArrayList<>(ids);
        Collections.reverse(ordered);

        statements.reset();
        personas.reorder(owner, ordered);

        assertEquals(1, statements.count(sql -> {
            var lower = sql.toLowerCase(Locale.ROOT);
            return lower.startsWith("select") && lower.contains(" from persona ") && lower.contains(" for ") && lower.contains("update");
        }), statements.statements().toString());
        for (int index = 0; index < ordered.size(); index++)
            assertEquals(index, personas.get(owner, ordered.get(index)).displayPriority());
        var missing = new ArrayList<>(ordered);
        missing.add(UUID.randomUUID());
        assertThrows(ChatException.class, () -> personas.reorder(owner, missing));
        var builtin = new ArrayList<>(ordered);
        builtin.add(sessions.create(owner, "Builtin").personaId());
        assertThrows(ChatException.class, () -> personas.reorder(owner, builtin));
    }

    @Test
    void contextFilesAreReadInOneStatement() {
        var files = List.of(readyFile(owner), readyFile(owner), readyFile(owner));
        var foreign = readyFile(other);
        var agent = personas.create(owner, input("Files", List.of(), List.of(), false, files));
        var session = sessions.create(owner, "Files");
        personas.select(owner, session.id(), agent.id());

        statements.reset();
        var reserved = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Read the files");

        assertEquals(1, statements.count("substring(plaintext"), statements.statements().toString());
        assertEquals(Set.copyOf(files), Objects.requireNonNull(reserved.context()).fileTexts().keySet());
        assertEquals("Test", reserved.context().fileTexts().get(files.getFirst()).text());
        assertFalse(reserved.context().fileTexts().containsKey(foreign));
    }

    @Test
    void finishingATurnWritesAndReadsItsOutcomeWithoutReloadingTheMessage() {
        var session = sessions.create(owner, "Finish");
        var pair = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");

        statements.reset();
        var outcome = turns.finishAndRead(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "Answer", null, "model", 1L, 2L, null, List.of());

        assertEquals(new ChatTurnPersistence.TerminalOutcome(ChatMessage.Status.COMPLETED, null), outcome);
        // Session lock, the terminal UPDATE returning its outcome, and the session's activity time.
        assertEquals(3, statements.statements().size(), statements.statements().toString());
        statements.reset();
        var late = turns.finishAndRead(session.id(), pair.assistantMessageId(), ChatMessage.Status.FAILED,
                "Late", "CHAT_EXECUTION_FAILED", "model", 1L, 2L, null, List.of());
        assertEquals(new ChatTurnPersistence.TerminalOutcome(ChatMessage.Status.COMPLETED, null), late,
                "the terminal winner is reported, not the late write");

        // MEM-267: what the last model call carried is read back with the answer for the context gauge.
        turns.recordContext(session.id(), pair.assistantMessageId(), new ChatContextUse(9_000, 128_000));
        var answer = sessions.history(owner, session.id(), null, 100).stream()
                .filter(message -> message.id().equals(pair.assistantMessageId())).findFirst().orElseThrow();
        assertEquals(new ChatContextUse(9_000, 128_000), answer.context());
    }

    @Test
    void aSendReadsItsAgentOnceAndTheReservationOnlyRechecksItsRevision() {
        var session = sessions.create(owner, "Persona");
        var binding = ModelBinding.builder(new SpringAiLlmService("fixture", "fixture", Mockito.mock(ChatModel.class)),
                p -> p, ModelRequestPolicy.hosted( Tokenizers.o200k(), p -> p), 32000, 4096, false, false).build();

        clearInvocations(authorization);
        statements.reset();
        var agent = turns.agent(owner, session.id());
        var selection = new ChatTurnPersistence.ModelSelection(null, UUID.randomUUID(), null, binding,
                agent.persona().revision(), "", agent);
        var reserved = turns.reserve(owner, session.id(), session.rootMessageId(), UUID.randomUUID(), "Question",
                Duration.ofMinutes(2), 32000, selection);
        var context = turns.loadContext(owner, session.id(), reserved);

        assertEquals(1, statements.count("AS restricts_sources"), "one agent read: " + statements.statements());
        assertEquals(0, statements.count("SELECT tool_key FROM persona_tool"), "tools, servers and Sources come with it");
        assertEquals(1, statements.count(sql -> sql.contains("FOR SHARE OF p") && !sql.contains("AS restricts_sources")),
                "the reservation share-locks the agent's revision");
        verify(authorization, times(1)).effectiveCapabilities(owner);
        assertSame(reserved.context(), context);

        // An agent edited after it was read no longer admits the send that read it.
        jdbc.sql("UPDATE persona SET revision = revision + 1 WHERE id = :id").param("id", session.personaId()).update();
        var stale = new ChatTurnPersistence.ModelSelection(null, UUID.randomUUID(), null, binding,
                agent.persona().revision(), "", agent);
        turns.finish(session.id(), reserved.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer");
        assertEquals("CHAT_CONFLICT", assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(),
                reserved.assistantMessageId(), UUID.randomUUID(), "Again", Duration.ofMinutes(2), 32000, stale)).code());
    }

    @Test
    void promptShortcutsArePrivateUniqueAndPublicOnesAreManagedAndHideable() {
        var member = member(tenant);
        when(authorization.effectiveCapabilities(member)).thenReturn(Set.of(IamCapability.CHAT_READ, IamCapability.CHAT_WRITE));
        var own = shortcuts.create(member, new ChatPromptShortcutService.ShortcutInput("tomtat", "Tóm tắt báo cáo này", null), false);
        assertThrows(ChatException.class, () -> shortcuts.create(member, new ChatPromptShortcutService.ShortcutInput("TomTat", "x", null), false));
        assertThrows(ChatException.class, () -> shortcuts.create(member, new ChatPromptShortcutService.ShortcutInput("tóm\ntắt", "x", null), false));
        var spaced = shortcuts.create(member, new ChatPromptShortcutService.ShortcutInput(" Tóm tắt hợp đồng ", "Tóm tắt điều khoản chính", null), false);
        assertEquals("Tóm tắt hợp đồng", spaced.name());
        shortcuts.delete(member, spaced.id(), false);
        assertThrows(ChatException.class, () -> shortcuts.create(member, new ChatPromptShortcutService.ShortcutInput("kpi", "x", null), true));
        var shared = shortcuts.create(owner, new ChatPromptShortcutService.ShortcutInput("kpi", "Xếp loại KPI tháng này", null), true);
        assertThrows(ChatException.class, () -> shortcuts.update(other, own.id(), own.revision(),
                new ChatPromptShortcutService.ShortcutInput("tomtat", "Changed", null), false));
        assertEquals(List.of("tomtat", "kpi"), shortcuts.list(member, false).stream().map(PromptShortcut::name).toList());
        shortcuts.hide(member, shared.id(), true);
        assertEquals(List.of("tomtat"), shortcuts.list(member, false).stream().map(PromptShortcut::name).toList());
        assertTrue(shortcuts.list(member, true).stream().anyMatch(PromptShortcut::hidden));
        assertTrue(shortcuts.preferences(member).enabled());
        assertFalse(shortcuts.preferences(member, false).enabled());
    }

    private UUID group(String name, Map<ActorId, Boolean> members) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES (:tenant,:id,:name)").param("tenant", tenant).param("id", id).param("name", name).update();
        members.forEach((actor, manager) -> jdbc.sql("""
                        INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id,is_manager) VALUES (:tenant,:group,:actor,:manager)
                        """).param("tenant", tenant).param("group", id).param("actor", actor.value()).param("manager", manager).update());
        return id;
    }

    private ChatTurnPersistence.Reservation reserve(ChatSession session, UUID parent, UUID request, String text) {
        return turns.reserve(owner, session.id(), parent, request, text, Duration.ofMinutes(2), 32000);
    }

    /** One finished question and answer, so the conversation is a topic the lists show. */
    private void ask(ActorId actor, ChatSession session, String question, String answer) {
        var reply = turns.reserve(actor, session.id(), session.rootMessageId(), UUID.randomUUID(), question,
                Duration.ofMinutes(2), 32000);
        turns.finish(session.id(), reply.assistantMessageId(), ChatMessage.Status.COMPLETED, answer);
    }

    @Test
    void orderedMessageFilesSurviveReplayRegenerationEditingAndSharedHistoryWithoutNewUploads() {
        var a = readyFile(owner); var b = readyFile(owner); var foreign = readyFile(other);
        var session = sessions.create(owner, "Files");
        var denied = ChatCommand.builder(ChatCommand.Operation.SEND, session.rootMessageId(), UUID.randomUUID(), "")
                .fileIds(List.of(foreign))
                .build();
        // The library refuses the file; its failure answers the same CHAT_UNAVAILABLE problem Chat's would.
        var refused = assertThrows(LibraryException.class, () -> turns.reserve(owner, session.id(), denied, Duration.ofMinutes(2), 32000, null));
        assertEquals("CHAT_UNAVAILABLE", refused.code());
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        var command = ChatCommand.builder(ChatCommand.Operation.SEND, session.rootMessageId(), UUID.randomUUID(), "")
                .fileIds(List.of(b, a))
                .build();
        var first = turns.reserve(owner, session.id(), command, Duration.ofMinutes(2), 32000, null);
        var replay = turns.reserve(owner, session.id(), command, Duration.ofMinutes(2), 32000, null);
        assertFalse(replay.created());
        assertEquals(first.assistantMessageId(), replay.assistantMessageId());
        assertEquals(List.of(b, a), turns.loadContext(owner, session.id(), replay).newestFirst().getFirst().files().stream().map(ChatFileDescriptor::id).toList());
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(), ChatCommand.builder(command.operation(),
                command.targetMessageId(), command.requestId(), "")
                .fileIds(List.of(a, b))
                .build(), Duration.ofMinutes(2), 32000, null));
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "File answer");
        var regenerated = turns.reserve(owner, session.id(), ChatCommand.builder(ChatCommand.Operation.REGENERATE,
                first.userMessageId(), UUID.randomUUID(), "").build(), Duration.ofMinutes(2), 32000, null);
        assertEquals(first.userMessageId(), regenerated.userMessageId());
        assertNotNull(regenerated.context());
        assertEquals(List.of(b, a), regenerated.context().newestFirst().getFirst().files().stream().map(ChatFileDescriptor::id).toList());
        turns.finish(session.id(), regenerated.assistantMessageId(), ChatMessage.Status.COMPLETED, "Regenerated answer");
        var edited = turns.reserve(owner, session.id(), ChatCommand.builder(ChatCommand.Operation.EDIT, first.userMessageId(), UUID.randomUUID(), "")
                .fileIds(List.of(a))
                .build(), Duration.ofMinutes(2), 32000, null);
        turns.finish(session.id(), edited.assistantMessageId(), ChatMessage.Status.COMPLETED, "Edited answer");
        assertEquals(List.of(a), sessions.history(owner, session.id(), null, 100).getFirst().files().stream().map(ChatFileDescriptor::id).toList());
        var original = new JdbcChatRepository(jdbc).message(session.id(), first.userMessageId()).orElseThrow();
        assertEquals(List.of(b, a), original.files().stream().map(ChatFileDescriptor::id).toList());
        collaboration.share(owner, session.id(), true, 0);
        assertEquals(List.of(a), collaboration.sharedHistory(other, session.id(), null, 100).getFirst().files().stream().map(ChatFileDescriptor::id).toList());
        assertEquals(3, jdbc.sql("SELECT count(*) FROM object_uploads").query(Integer.class).single());
    }

    @Test
    void customPersonaEmptyFileListOverridesProjectAndReloadContextDoesNotUseReadOnlyLocks() {
        var file = readyFile(owner);
        var project = projects.create(owner, new ChatProjectService.ProjectInput("Files", "", "", List.of(file)));
        var session = projects.createConversation(owner, project.id(), "Files");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Read project");
        assertNotNull(first.context());
        assertEquals(List.of(file), first.context().workspaceFiles().stream().map(ChatFileDescriptor::id).toList());
        var reloaded = turns.loadContext(owner, session.id(), new ChatTurnPersistence.Reservation(first.userMessageId(), first.assistantMessageId(), false));
        assertEquals(List.of(file), reloaded.workspaceFiles().stream().map(ChatFileDescriptor::id).toList());
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Project answer");
        var persona = personas.create(owner, input("No files", List.of(), List.of(), false, List.of()));
        personas.select(owner, session.id(), persona.id());
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Custom assistant");
        assertNotNull(second.context());
        assertTrue(second.context().workspaceFiles().isEmpty());
        turns.finish(session.id(), second.assistantMessageId(), ChatMessage.Status.COMPLETED, "No project files");
        var settings = input("With files", List.of(), List.of(), false, List.of(file));
        personas.update(owner, persona.id(), persona.revision(), settings);
        assertThrows(ChatException.class, () -> personas.update(owner, persona.id(), persona.revision(), settings));
        var third = reserve(session, second.assistantMessageId(), UUID.randomUUID(), "Read custom files");
        assertNotNull(third.context());
        assertEquals(List.of(file), third.context().workspaceFiles().stream().map(ChatFileDescriptor::id).toList());
        assertTrue(second.context().workspaceFiles().isEmpty());
    }

    private static ChatPersonaService.PersonaInput input(String name, List<String> starters, List<UUID> sources, boolean search,
            List<UUID> files) {
        return input(name, "", starters, sources, search, files);
    }

    @Test
    void anAgentKeepsTheFaceItWasGivenAndOnlyAValidSeedChangesIt() {
        var first = personas.create(owner, input("Kế toán", "", List.of(), List.of(), false, null));
        var second = personas.create(owner, input("Nhân sự", "", List.of(), List.of(), false, null));
        assertTrue(first.avatarSeed().matches(ChatPersonaService.PersonaInput.AVATAR_SEED_PATTERN));
        assertNotEquals(first.avatarSeed(), second.avatarSeed());

        var renamed = personas.update(owner, first.id(), first.revision(), input("Kế toán tổng hợp", "", List.of(), List.of(), false, null));
        assertEquals(first.avatarSeed(), renamed.avatarSeed(), "renaming keeps the face");

        var chosen = personas.update(owner, first.id(), renamed.revision(), avatar(renamed.name(), "kt-2026_a"));
        assertEquals("kt-2026_a", chosen.avatarSeed());
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class,
                () -> personas.update(owner, first.id(), chosen.revision(), avatar(chosen.name(), "không hợp lệ"))).code());

        assertEquals("memoryos", personas.get(owner, sessions.create(owner, "Builtin").personaId()).avatarSeed());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM persona WHERE avatar_seed IS NULL OR avatar_seed = ''").query(Integer.class).single());
    }

    private static ChatPersonaService.PersonaInput avatar(String name, String seed) {
        return new ChatPersonaService.PersonaInput(name, null, "", "", null, List.of(), List.of(), null, Set.of(), null, null,
                null, null, seed, null, null, null, null);
    }

    private static ChatPersonaService.PersonaInput input(String name, String instructions, List<String> starters, List<UUID> sources,
            boolean search, List<UUID> files) {
        return new ChatPersonaService.PersonaInput(name, null, "", instructions, null, starters, sources, null,
                search ? Set.of("search") : Set.of(), null, null, null, files, null, null, null, null, null);
    }

    private static String undated(String prompt) {
        return prompt.replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "<now>");
    }

    private UUID readyFile(ActorId actor) {
        return Objects.requireNonNull(tx.execute(ignored -> {
            var tenantId = new TenantId(tenant);
            var objectId = new StoredObjectId(UUID.randomUUID());
            var uploadId = new ObjectUploadId(UUID.randomUUID());
            var spec = new ObjectUploadSpecification("Ghi chú.txt", "text/plain", 4,
                    new ContentSha256("a".repeat(64)), ObjectUploadPurpose.CHAT_FILE);
            new JdbcStoredObjectRepository(jdbc).create(tenantId, objectId,
                    new ObjectKey("raw/" + tenant + "/" + objectId.value()), spec, Instant.now().plusSeconds(600));
            new JdbcObjectUploadRepository(jdbc).create(tenantId, uploadId, objectId, spec.purpose());
            var id = new JdbcUserFileRepository(jdbc).create(tenantId, actor, UUID.randomUUID(), uploadId, spec);
            // This suite tests message transactions; worker/adoption publication is exercised separately.
            jdbc.sql("UPDATE chat_user_file SET status='READY',plaintext='Test',detected_media_type='text/plain',"
                    + "stored_object_id=:object WHERE id=:id").param("object", objectId.value()).param("id", id).update();
            return id;
        }));
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Chat', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        // Tenant provisioning creates the built-in agent in production; the raw fixture does it here.
        var defaults = new PersonaProperties();
        new JdbcChatRepository(jdbc).provisionPersona(new TenantId(id), defaults.getName(), defaults.getInstructions());
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
