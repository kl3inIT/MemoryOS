package io.memoryos.chat;

import io.memoryos.chat.persistence.JdbcPromptShortcutRepository;
import io.memoryos.chat.persistence.JdbcAgentRepository;
import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.TestDatabase;
import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.application.DefaultChatSessionService;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcChatSearchRepository;
import io.memoryos.chat.persistence.JpaPersonaRepository;
import io.memoryos.chat.persistence.JpaProjectRepository;
import io.memoryos.chat.persistence.JpaChatSharingRepository;
import io.memoryos.chat.persistence.JpaChatFeedbackRepository;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.tenant.TenantId;
import java.util.Map;
import java.util.Set;
import static org.mockito.Mockito.*;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.chat.execution.ChatRequestPolicy;
import io.memoryos.chat.execution.ChatTurnSetup;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.chat.model.ChatModel;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.ActorLanguageService;
import io.memoryos.iam.identity.persistence.ActorRefreshImpl;
import io.memoryos.iam.identity.persistence.JpaActorRepository;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;

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
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
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
    private IamAuthorization authorization;
    private ChatPromptShortcutService shortcuts;
    private DocumentSetService documentSets;
    private SourceSearchService sourceScope;

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
        authorization = mock(IamAuthorization.class);
        sessions = TestDatabase.transactionalProxy(new DefaultChatSessionService(tenants, authorization, repository, new PersonaProperties(), new JdbcChatSearchRepository(jdbc)),
                ChatSessionService.class, jpa.transactionManager());
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(jpa.transactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var fileService = new ChatFileService(tenants, repository, new io.memoryos.chat.persistence.JdbcUserFileRepository(jdbc),
                mock(io.memoryos.objectstorage.ObjectUploadService.class),
                new io.memoryos.chat.application.ChatFileProperties(104857600, 262144000),
                new ChatStorageQuotaService(tenants, authorization,
                        new io.memoryos.chat.persistence.JdbcChatStorageQuotaRepository(jdbc),
                        new io.memoryos.chat.persistence.JdbcChatLibraryRepository(jdbc)),
                new io.memoryos.chat.application.ChatRetentionProperties(false, java.time.Duration.ZERO,
                        java.time.Duration.ZERO, java.time.Duration.ofHours(24)),
                jpa.transactionManager());
        var factory = new ProxyFactory(new ChatTurnPersistence(tenants, authorization, repository, new PersonaProperties(), fileService,
                new ActorLanguageService(jpa.repository(JpaActorRepository.class,
                        RepositoryFragments.just(new ActorRefreshImpl(jpa.entityManager()))), tenants),
                new io.memoryos.chat.persistence.JdbcImageArtifactRepository(jdbc)));
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        turns = (ChatTurnPersistence) factory.getProxy();
        tenant = tenant();
        owner = member(tenant);
        other = member(tenant);
        when(authorization.effectiveCapabilities(owner)).thenReturn(Set.of(IamCapability.MODELS_MANAGE, IamCapability.AGENTS_MANAGE, IamCapability.AGENTS_CREATE));
        when(authorization.effectiveCapabilities(other)).thenReturn(Set.of());
        var models = mock(ModelCatalogService.class);
        when(models.availableModelsForPersona(any(), any())).thenReturn(List.of(new ModelCatalogService.AvailableModel(
                UUID.randomUUID(), UUID.randomUUID(), "Provider", "model", "Model",
                new ModelSettings.Capabilities(true, true, false, false), 32000, 4096, null, true)));
        var sources = mock(SourceSearchService.class); sourceScope = sources; sourceId = UUID.randomUUID();
        when(sources.scope(any())).thenAnswer(call -> new SourceSearchScope(new TenantId(tenant), call.getArgument(0), Map.of(sourceId, SourceType.FILE)));
        var agentRows = new io.memoryos.chat.persistence.JdbcAgentRepository(jdbc);
        var documentSetRows = new io.memoryos.chat.persistence.JdbcDocumentSetRepository(jdbc);
        documentSets = service(new DocumentSetService(tenants, authorization, sources, agentRows, documentSetRows), DocumentSetService.class);
        personas = service(new ChatPersonaService(tenants, authorization, repository, jpa.repository(JpaPersonaRepository.class),
                agentRows, new io.memoryos.chat.persistence.PersonaRevisions(jpa.entityManager()),
                new PersonaProperties(), models, sources, documentSets, documentSetRows, fileService,
                new io.memoryos.chat.persistence.JdbcUserFileRepository(jdbc), mock(ChatFileContentService.class)), ChatPersonaService.class);
        projects = service(new ChatProjectService(tenants, authorization, repository, jpa.repository(JpaProjectRepository.class), sessions, fileService), ChatProjectService.class);
        shortcuts = service(new ChatPromptShortcutService(tenants, authorization, repository,
                new io.memoryos.chat.persistence.JdbcPromptShortcutRepository(jdbc)), ChatPromptShortcutService.class);
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
        var agent = personas.create(owner, new ChatPersonaService.PersonaInput("Finance assistant", "", "Use reports.", null,
                List.of(), List.of(), List.of(set.id()), Set.of("search"), null, null, null, null, List.of(), null, null,
                null, false, false, null));

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
        var agent = personas.create(owner, new ChatPersonaService.PersonaInput("Reports", "", "Use reports.", null,
                List.of(), List.of(), List.of(set.id()), Set.of("search"), null, null, null, null, List.of(), null, null,
                null, false, false, null));
        personas.share(owner, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(
                List.of(), List.of(), true, JdbcAgentRepository.Permission.VIEWER));

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
        var assistant = personas.create(owner, input("Private", List.of("A starter"), List.of(sourceId), false, 8000, 1000, null));
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
    void generatedImagesAreNamedInLaterContextAndEditSourcesStayInTheirSession() {
        var images = new io.memoryos.chat.persistence.JdbcImageArtifactRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Images");
        var first = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Draw a man");
        var image = UUID.randomUUID();
        images.insert(scope, first.assistantMessageId(), image, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/image.png"), "image/png", ".png", 11, null, null, null);
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "Here he is.");
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Make the shirt red");
        assertEquals(List.of(image), turns.loadContext(owner, session.id(), second).generatedImages().get(first.assistantMessageId()));

        var edited = UUID.randomUUID();
        images.insert(scope, second.assistantMessageId(), edited, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/edited.png"), "image/png", ".png", 12, null, image, null);
        assertEquals(image, jdbc.sql("SELECT source_artifact_id FROM chat_image_artifact WHERE id = :id")
                .param("id", edited).query(UUID.class).single());
        assertTrue(images.inSession(scope, owner, session.id(), image).isPresent());
        assertTrue(images.inSession(scope, other, session.id(), image).isEmpty());
        var elsewhere = sessions.create(owner, "Elsewhere");
        assertTrue(images.inSession(scope, owner, elsewhere.id(), image).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> images.insert(scope, second.assistantMessageId(), UUID.randomUUID(),
                UUID.randomUUID(), new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/both.png"), "image/png", ".png", 13,
                null, image, UUID.randomUUID()));
    }

    @Test
    void interpreterSettingRevisesAndGeneratedFilesServeOnlyTheirOwner() {
        var interpreter = new io.memoryos.chat.interpreter.JdbcInterpreterRepository(jdbc);
        var scope = new TenantId(tenant);
        assertTrue(interpreter.setting(scope).isEmpty());
        assertEquals(new io.memoryos.chat.interpreter.JdbcInterpreterRepository.Setting(true, 1), interpreter.save(scope, true));
        assertEquals(new io.memoryos.chat.interpreter.JdbcInterpreterRepository.Setting(false, 2), interpreter.save(scope, false));

        var session = sessions.create(owner, "Code");
        var reply = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Make a chart");
        var file = UUID.randomUUID();
        interpreter.insertArtifact(scope, reply.assistantMessageId(), file, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/chart.png"), "chart.png", "image/png", 3,
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
        assertTrue(interpreter.attachPreview(scope, file, UUID.randomUUID(), new io.memoryos.objectstorage.ObjectKey("p/1"), 10));
        assertFalse(interpreter.attachPreview(scope, file, UUID.randomUUID(), new io.memoryos.objectstorage.ObjectKey("p/2"), 10));
        assertEquals("p/1", interpreter.ownedArtifact(scope, owner, file).orElseThrow().previewKey().value());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> interpreter.insertArtifact(scope,
                reply.assistantMessageId(), UUID.randomUUID(), UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/x.png"), "x.png", "image/png", 3, "[1]"));
        assertTrue(interpreter.ownedArtifact(new TenantId(UUID.randomUUID()), owner, file).isEmpty());
    }

    @Test
    void theFileLibraryUnionsEverySourceForItsOwnerAndHidesWhatWasDeleted() {
        var library = new io.memoryos.chat.persistence.JdbcChatLibraryRepository(jdbc);
        var interpreter = new io.memoryos.chat.interpreter.JdbcInterpreterRepository(jdbc);
        var images = new io.memoryos.chat.persistence.JdbcImageArtifactRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Báo cáo");
        var reply = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Make a workbook");
        var upload = readyFile(owner);
        var generated = UUID.randomUUID();
        interpreter.insertArtifact(scope, reply.assistantMessageId(), generated, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/doanh-thu.xlsx"), "doanh-thu.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 2048, null);
        var image = UUID.randomUUID();
        images.insert(scope, reply.assistantMessageId(), image, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/pic.png"), "image/png", ".png", 512,
                "a red shirt", null, null);

        var all = library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50);
        assertEquals(3, all.totalCount());
        assertEquals(4 + 2048 + 512, all.totalBytes());
        assertEquals(List.of(ChatLibraryFile.Source.IMAGE, ChatLibraryFile.Source.GENERATED, ChatLibraryFile.Source.UPLOAD),
                all.items().stream().map(ChatLibraryFile::source).toList());
        var workbook = all.items().stream().filter(file -> file.id().equals(generated)).findFirst().orElseThrow();
        assertEquals(ChatLibraryFile.Category.SPREADSHEET, workbook.category());
        assertEquals(session.id(), workbook.sessionId());
        assertEquals("Báo cáo", workbook.sessionTitle());
        // An upload belongs to its owner, not to one conversation.
        assertNull(all.items().stream().filter(file -> file.id().equals(upload)).findFirst().orElseThrow().sessionId());
        // A generated image is named and measured, and its prompt is searchable (V90).
        var picture = all.items().stream().filter(file -> file.id().equals(image)).findFirst().orElseThrow();
        assertTrue(picture.filename().startsWith("image-") && picture.filename().endsWith(".png"), picture.filename());
        assertEquals(512, picture.sizeBytes());
        assertEquals(List.of(image), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("red shirt", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));

        assertEquals(List.of(generated), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("doanh", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(generated), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of("GENERATED"), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(upload), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of("DOCUMENT"), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(generated, image, upload),
                ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.LARGEST, 0, 50)));
        var second = library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 1, 1);
        assertEquals(3, second.totalCount());
        assertEquals(List.of(generated), ids(second));
        // A page past the end still reports the filter's totals; they describe the filter, not the page.
        var beyond = library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 10, 50);
        assertEquals(List.of(), ids(beyond));
        assertEquals(3, beyond.totalCount());
        assertEquals(4 + 2048 + 512, beyond.totalBytes());
        // A search term is matched literally, not as an ILIKE pattern.
        assertEquals(List.of(), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("%", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(0, library.page(scope, other, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50).totalCount());

        // Deleting a generated file hides it everywhere and refuses a preview that was converting meanwhile.
        assertTrue(interpreter.markArtifactDeleted(scope, owner, generated, java.time.Duration.ZERO));
        assertFalse(interpreter.markArtifactDeleted(scope, other, generated, java.time.Duration.ZERO));
        assertTrue(interpreter.ownedArtifact(scope, owner, generated).isEmpty());
        assertFalse(interpreter.attachPreview(scope, generated, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("p/late"), 10));
        assertTrue(images.markDeleted(scope, owner, image, java.time.Duration.ZERO));
        // Deleting again succeeds while another member still cannot delete the same image.
        assertTrue(images.markDeleted(scope, owner, image, java.time.Duration.ZERO));
        assertFalse(images.markDeleted(scope, other, image, java.time.Duration.ZERO));
        assertTrue(images.inSession(scope, owner, session.id(), image).isEmpty());
        assertEquals(List.of(upload), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        // History keeps both as tombstones so the answer does not silently lose its cards.
        assertTrue(interpreter.byMessages(scope, List.of(reply.assistantMessageId()))
                .get(reply.assistantMessageId()).getFirst().deleted());
        assertTrue(images.byMessages(scope, List.of(reply.assistantMessageId()), true)
                .get(reply.assistantMessageId()).getFirst().deleted());
        assertTrue(images.byMessages(scope, List.of(reply.assistantMessageId()), false).isEmpty());
        // Once the sweep has removed the row, the owner's repeated delete is still the outcome they asked for.
        jdbc.sql("DELETE FROM chat_image_artifact WHERE id=:id").param("id", image).update();
        assertTrue(images.markDeleted(scope, owner, image, java.time.Duration.ZERO));

        // Deleting the conversation withdraws its artifacts from the library; the upload is the owner's.
        var kept = sessions.create(owner, "Kept");
        var keptReply = reserve(kept, kept.rootMessageId(), UUID.randomUUID(), "One more");
        var keptFile = UUID.randomUUID();
        interpreter.insertArtifact(scope, keptReply.assistantMessageId(), keptFile, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/notes.pdf"), "notes.pdf", "application/pdf", 8, null);
        assertEquals(List.of(keptFile, upload), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        turns.delete(owner, kept.id());
        assertEquals(List.of(upload), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50)));

        // An upload a project holds is named rather than silently undeletable.
        var project = projects.create(owner, new ChatProjectService.ProjectInput("Kế hoạch", "", "", List.of(upload)));
        var usage = new io.memoryos.chat.persistence.JdbcUserFileRepository(jdbc).usage(scope, List.of(upload));
        assertEquals(List.of("Kế hoạch"), usage.stream().map(io.memoryos.chat.persistence.JdbcUserFileRepository.Usage::name).toList());
        assertEquals(io.memoryos.chat.persistence.JdbcUserFileRepository.Usage.Kind.PROJECT, usage.getFirst().kind());
        assertEquals(project.id(), usage.getFirst().id());
    }

    @Test
    void aConversationsOwnFilesAreItsArtifactsAndTheUploadsAttachedInIt() {
        var library = new io.memoryos.chat.persistence.JdbcChatLibraryRepository(jdbc);
        var images = new io.memoryos.chat.persistence.JdbcImageArtifactRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Có tệp");
        var elsewhere = sessions.create(owner, "Nơi khác");
        var attached = readyFile(owner);
        var unattached = readyFile(owner);
        var reply = turns.reserve(owner, session.id(), new ChatCommand(ChatCommand.Operation.SEND,
                session.rootMessageId(), UUID.randomUUID(), "Xem tệp này", null, List.of(attached)),
                Duration.ofMinutes(2), 32000, null);
        var image = UUID.randomUUID();
        images.insert(scope, reply.assistantMessageId(), image, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/in-session.png"), "image/png", ".png", 64,
                null, null, null);
        var otherReply = reserve(elsewhere, elsewhere.rootMessageId(), UUID.randomUUID(), "Khác");
        var otherImage = UUID.randomUUID();
        images.insert(scope, otherReply.assistantMessageId(), otherImage, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/elsewhere.png"), "image/png", ".png", 32,
                null, null, null);

        var inSession = library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), session.id()), ChatLibraryFile.Sort.NEWEST, 0, 50);
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
        assertTrue(ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null), ChatLibraryFile.Sort.NEWEST, 0, 50))
                .containsAll(List.of(image, otherImage, attached, unattached)));
        // A conversation the caller does not own matches nothing, including their own upload used in it.
        assertEquals(List.of(), ids(library.page(scope, other, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), session.id()), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(), ids(library.page(scope, owner, io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), UUID.randomUUID()), ChatLibraryFile.Sort.NEWEST, 0, 50)));
    }

    @Test
    void theLibraryRenamesStarsAndListsPendingUploadsOnlyForTheirOwner() {
        var library = new io.memoryos.chat.persistence.JdbcChatLibraryRepository(jdbc);
        var interpreter = new io.memoryos.chat.interpreter.JdbcInterpreterRepository(jdbc);
        var scope = new TenantId(tenant);
        var session = sessions.create(owner, "Tệp");
        var reply = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Make a workbook");
        var upload = readyFile(owner);
        var failed = readyFile(owner);
        jdbc.sql("UPDATE chat_user_file SET status='FAILED', error_code='EXTRACTION_FAILED' WHERE id=:id").param("id", failed).update();
        var generated = UUID.randomUUID();
        interpreter.insertArtifact(scope, reply.assistantMessageId(), generated, UUID.randomUUID(),
                new io.memoryos.objectstorage.ObjectKey("tenants/" + tenant + "/bang.xlsx"), "bang.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 64, null);
        var filter = io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("", Set.of(), Set.of(), null);

        // The READY list is unchanged; the pending list holds only the failed upload, with its reason.
        assertEquals(List.of(generated, upload), ids(library.page(scope, owner, filter, ChatLibraryFile.Sort.NEWEST, 0, 50)));
        var pending = library.page(scope, owner, new io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter("",
                Set.of(), Set.of(), null, false, true, null), ChatLibraryFile.Sort.NEWEST, 0, 50);
        assertEquals(List.of(failed), ids(pending));
        assertEquals(UserFile.Status.FAILED, pending.items().getFirst().status());
        assertEquals("EXTRACTION_FAILED", pending.items().getFirst().errorCode());

        // A rename rewrites the file's own name, so the name search and the listing follow it.
        assertTrue(library.update(scope, owner, ChatLibraryFile.Source.GENERATED, generated, "Doanh thu quý 3.xlsx", null));
        assertTrue(library.update(scope, owner, ChatLibraryFile.Source.UPLOAD, upload, null, true));
        assertEquals(List.of(generated), ids(library.page(scope, owner,
                io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter.of("quý 3", Set.of(), Set.of(), null),
                ChatLibraryFile.Sort.NEWEST, 0, 50)));
        assertEquals(List.of(generated, upload), ids(library.page(scope, owner, filter, ChatLibraryFile.Sort.NAME, 0, 50)));
        var starred = library.page(scope, owner, new io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter("",
                Set.of(), Set.of(), null, true, false, null), ChatLibraryFile.Sort.NEWEST, 0, 50);
        assertEquals(List.of(upload), ids(starred));
        assertTrue(starred.items().getFirst().favorite());
        // Unstarring clears it; another member can neither rename nor star the file.
        assertTrue(library.update(scope, owner, ChatLibraryFile.Source.UPLOAD, upload, null, false));
        assertEquals(List.of(), ids(library.page(scope, owner, new io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter("",
                Set.of(), Set.of(), null, true, false, null), ChatLibraryFile.Sort.NEWEST, 0, 50)));
        assertFalse(library.update(scope, other, ChatLibraryFile.Source.GENERATED, generated, "x.xlsx", true));
        assertFalse(library.update(scope, owner, ChatLibraryFile.Source.IMAGE, generated, "x.png", null));
        // A deleted artifact is no longer the owner's to change either.
        jdbc.sql("UPDATE chat_file_artifact SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id").param("id", generated).update();
        assertFalse(library.update(scope, owner, ChatLibraryFile.Source.GENERATED, generated, null, true));
        // Only the listed ids remain when the filter names them.
        assertEquals(List.of(upload), ids(library.page(scope, owner, new io.memoryos.chat.persistence.JdbcChatLibraryRepository.Filter("",
                Set.of(), Set.of(), null, false, false, Set.of(upload)), ChatLibraryFile.Sort.NEWEST, 0, 50)));
    }

    private static List<UUID> ids(io.memoryos.chat.persistence.JdbcChatLibraryRepository.Page page) {
        return page.items().stream().map(ChatLibraryFile::id).toList();
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
        assertTrue(sessions.list(owner, false, 0, 100).isEmpty());
    }

    @Test
    void oversizedQuestionRollsBackBeforeTreeAdvancesAndBuiltinConfigurationSurvivesSend() {
        var session = sessions.create(owner, "Validation");
        var request = UUID.randomUUID();
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(), session.rootMessageId(), request,
                "Question ".repeat(500), Duration.ofMinutes(2), 100));
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        jdbc.sql("UPDATE persona SET instructions = 'Answer' WHERE id = :id").param("id", session.personaId()).update();
        var tokens = new JTokkitTokenCountEstimator(EncodingType.O200K_BASE);
        var policy = ChatRequestPolicy.hosted(tokens, p -> p);
        var binding = new ChatModelBinding(new SpringAiLlmService("fixture", "fixture",
                org.mockito.Mockito.mock(ChatModel.class)), p -> p, policy, 32000, 4096, false, false);
        String contribution = "Current date: 2026-09-11\n";
        // Reservation validates and stores the resolved prompt, including the account-language block.
        String instructions = ChatTurnSetup.instructions(
                io.memoryos.chat.prompts.ChatPrompts.resolve("Answer", false, java.time.Instant.now(), "vi"),
                contribution);
        int raw = tokens.estimate(instructions) + tokens.estimate("Question");
        var selection = new ChatTurnPersistence.ModelSelection(null, UUID.randomUUID(), null, binding, null, contribution);
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(), session.rootMessageId(), request,
                "Question", Duration.ofMinutes(2), raw, selection));
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        jdbc.sql("UPDATE persona SET model = 'obsolete-model' WHERE id = :id").param("id", session.personaId()).update();
        var reservation = turns.reserve(owner, session.id(), session.rootMessageId(), request, "Question", Duration.ofMinutes(2), raw + 64, selection);
        assertEquals("obsolete-model", turns.loadContext(owner, session.id(), reservation).model());
        var setup = ChatTurnSetup.resolve(session.id(), reservation.assistantMessageId(),
                turns.loadContext(owner, session.id(), reservation), raw + 64, binding, contribution);
        assertEquals(List.of(instructions, "Question"), setup.messages().stream().map(com.embabel.chat.Message::getContent).toList());
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
        var artifact = new ChatArtifact(UUID.randomUUID(), "Allowance", "{\"root\":{\"component\":\"Metric\",\"props\":{\"label\":\"Days\",\"value\":\"12\"}}}");
        var activity = new ChatActivity(List.of(new ChatActivity.ActivityStep(1, "call_1", "search_knowledge", ChatActivity.StepStatus.COMPLETED,
                java.time.Instant.parse("2026-09-14T00:00:00Z"), 120L, 0, List.of("leave policy"), null, List.of(), List.of(1))),
                List.of(new ChatActivity.ReasoningSegment(0, 0, "Checking the HR policy.")));
        turns.finishAndRead(session.id(), pair.assistantMessageId(), ChatMessage.Status.CANCELED,
                "Twelve days [1]", null, "model", 10L, 4L, null, List.of(source), List.of(artifact), activity);
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
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.sql(
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
    void startupReconciliationFailsRunningRowsWithLiveLeasesAndLeavesFinishedRows() {
        var session = sessions.create(owner, "Orphaned");
        var orphaned = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        var other = sessions.create(owner, "Finished");
        var finished = reserve(other, other.rootMessageId(), UUID.randomUUID(), "Question");
        assertTrue(turns.finish(other.id(), finished.assistantMessageId(), ChatMessage.Status.COMPLETED, "Answer"));
        // The lease is still live: only startup knows no process owns the row.
        assertEquals(0, turns.expireRuns());
        assertTrue(turns.failOrphanedRuns() >= 1);
        assertEquals(0, turns.failOrphanedRuns());
        assertEquals(ChatMessage.Status.FAILED, turns.authorizeReply(owner, session.id(), orphaned.assistantMessageId()));
        assertEquals("CHAT_INTERRUPTED", jdbc.sql("SELECT failure_code FROM chat_message WHERE id = :id")
                .param("id", orphaned.assistantMessageId()).query(String.class).single());
        assertEquals(ChatMessage.Status.COMPLETED, turns.authorizeReply(owner, other.id(), finished.assistantMessageId()));
        assertFalse(turns.finish(session.id(), orphaned.assistantMessageId(), ChatMessage.Status.COMPLETED, "late"));
        // The session accepts the next question at once.
        reserve(session, orphaned.assistantMessageId(), UUID.randomUUID(), "Next question");
    }

    @Test
    void answerStoresMoreThanTwentyFourSourcesAndTheColumnKeepsAByteBound() {
        var session = sessions.create(owner, "Many sources");
        var pair = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        var sources = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(index -> new ChatSource(index, null, null, "File " + index, 0, 0, List.of(), UUID.randomUUID())).toList();
        assertTrue(new JdbcChatRepository(jdbc).finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "Answer [30]", null, null, null, null, null, sources));
        assertEquals(30, sessions.history(owner, session.id(), null, 20).getLast().sources().size());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.sql(
                "UPDATE chat_message SET sources = jsonb_build_array(repeat('x', 1048576)) WHERE id = :id")
                .param("id", pair.assistantMessageId()).update(), "Stored sources stay bounded to 1 MiB");
    }

    @Test
    void researchClarificationAndPlanAreStoredWithTheTerminalOutcomeAndBounded() {
        var session = sessions.create(owner, "Research state");
        var pair = reserve(session, session.rootMessageId(), UUID.randomUUID(), "Question");
        var agent = new ChatResearch.Agent("call_revenue", 0, 1, "Revenue in 2025", ChatActivity.StepStatus.COMPLETED, 900L,
                "Revenue grew [1].", List.of(new ChatResearchEvent.Citation(1, 4)), new ChatActivity(List.of(new ChatActivity.ActivityStep(0,
                "call_search", "search_knowledge", ChatActivity.StepStatus.COMPLETED, java.time.Instant.parse("2026-09-15T10:00:00Z"), 12L, 0,
                List.of("revenue"), null, List.of(), List.of())), List.of()));
        var state = new ChatResearch(true, "1. Revenue", List.of(agent));
        assertTrue(new JdbcChatRepository(jdbc).finish(session.id(), pair.assistantMessageId(), ChatMessage.Status.COMPLETED,
                "Which fiscal year?", null, null, null, null, null, List.of(), List.of(), ChatActivity.EMPTY, state));
        var history = sessions.history(owner, session.id(), null, 20);
        assertEquals(state, history.getLast().research());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.sql(
                "UPDATE chat_message SET research_agents = '[{}]'::jsonb WHERE id = :id").param("id", history.getFirst().id()).update(),
                "Only assistant messages carry research agents");
        assertEquals(ChatResearch.EMPTY, history.getFirst().research(), "Ordinary messages carry no research state");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.sql(
                "UPDATE chat_message SET research_plan = repeat('x', 100001) WHERE id = :id")
                .param("id", pair.assistantMessageId()).update(), "The stored plan stays bounded");
        assertThrows(IllegalArgumentException.class, () -> new ChatResearch(false, "x".repeat(ChatResearch.MAX_PLAN + 1)));
    }

    @Test
    void deepResearchModeIsPartOfCommandIdentity() {
        var session = sessions.create(owner, "Research identity");
        var request = UUID.randomUUID();
        var research = new ChatCommand(ChatCommand.Operation.SEND, session.rootMessageId(), request, "Question", null, List.of(),
                WebSearchMode.off, ImageMode.off, true);
        var reserved = turns.reserve(owner, session.id(), research, java.time.Duration.ofMinutes(30), 32000, null);
        assertTrue(reserved.created());
        assertTrue(jdbc.sql("SELECT deep_research FROM chat_command WHERE session_id = :session AND request_id = :request")
                .param("session", session.id()).param("request", request).query(Boolean.class).single());
        var replay = turns.reserve(owner, session.id(), research, java.time.Duration.ofMinutes(30), 32000, null);
        assertEquals(reserved.assistantMessageId(), replay.assistantMessageId());
        var ordinary = new ChatCommand(ChatCommand.Operation.SEND, session.rootMessageId(), request, "Question", null, List.of(),
                WebSearchMode.off, ImageMode.off, false);
        assertEquals("CHAT_CONFLICT", assertThrows(ChatException.class,
                () -> turns.reserve(owner, session.id(), ordinary, java.time.Duration.ofMinutes(30), 32000, null)).code());
    }

    @Test
    void createsPrivateSessionWithOneRootAndSharedDefaultPersona() {
        var first = sessions.create(owner, "First");
        var second = sessions.create(owner, "Second");
        assertEquals(first.personaId(), second.personaId());
        assertEquals(first, sessions.get(owner, first.id()));
        assertTrue(sessions.history(owner, first.id(), null, 20).isEmpty());
        assertEquals(2, sessions.list(owner, false, 0, 30).size());
        assertTrue(sessions.list(other, false, 0, 30).isEmpty());
        assertEquals("CHAT_UNAVAILABLE", assertThrows(ChatException.class, () -> sessions.get(other, first.id())).code());
        // The deployment schema permits one Tenant; verify the repository still scopes by its ID.
        assertTrue(new JdbcChatRepository(jdbc).findOwned(new io.memoryos.iam.tenant.TenantId(UUID.randomUUID()),
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
        assertEquals(java.util.Set.of(live.assistantMessageId(), lapsed.assistantMessageId()),
                turns.renewLeases(List.of(live.assistantMessageId(), lapsed.assistantMessageId()), java.time.Duration.ofMinutes(30)));
        assertEquals(0, turns.expireRuns(), "Renewed leases are not reconciled");
        jdbc.sql("UPDATE chat_message SET deadline_at = clock_timestamp() - interval '10 seconds' WHERE id = :id")
                .param("id", lapsed.assistantMessageId()).update();
        assertEquals(1, turns.expireRuns());
        assertEquals(ChatMessage.Status.RUNNING, turns.authorizeReply(owner, session.id(), live.assistantMessageId()));
        assertEquals(ChatMessage.Status.FAILED, turns.authorizeReply(owner, other.id(), lapsed.assistantMessageId()));
        // A reconciled row is not renewed, so its process learns it no longer owns the run.
        assertEquals(java.util.Set.of(live.assistantMessageId()),
                turns.renewLeases(List.of(live.assistantMessageId(), lapsed.assistantMessageId()), java.time.Duration.ofMinutes(30)));
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
                        input(before.name(), "Updated builtin", List.of(), List.of(sourceId), false, null, null, null)));
                try {
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
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
                new IamLockRepository(jdbc).lockTenant(new io.memoryos.iam.tenant.TenantId(tenant));
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

    @Test
    void agentSharingFollowsOnyxUseEditPublicManagerAndTransferRules() {
        var creator = member(tenant); var viewer = member(tenant); var groupEditor = member(tenant);
        var stranger = member(tenant); var manager = member(tenant);
        for (var actor : List.of(viewer, groupEditor, stranger, manager))
            when(authorization.effectiveCapabilities(actor)).thenReturn(Set.of(IamCapability.CHAT_READ, IamCapability.CHAT_WRITE));
        when(authorization.effectiveCapabilities(creator)).thenReturn(Set.of(IamCapability.CHAT_WRITE, IamCapability.AGENTS_CREATE));
        assertThrows(ChatException.class, () -> personas.create(stranger, input("Denied", List.of(), List.of(), true, null, null, List.of())));
        var agent = personas.create(creator, input("KPI", List.of("Xếp loại tháng 8"), List.of(sourceId), true, null, null, List.of()));
        assertTrue(agent.permissions().edit()); assertTrue(agent.permissions().setPublic()); assertTrue(agent.permissions().delete());
        assertThrows(ChatException.class, () -> personas.get(stranger, agent.id()));

        UUID editors = group("Ban điều hành", Map.of(groupEditor, false, manager, true));
        var shared = personas.share(creator, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(
                List.of(new ChatPersonaService.UserShareInput(viewer.value(), JdbcAgentRepository.Permission.VIEWER)),
                List.of(new ChatPersonaService.GroupShareInput(editors, JdbcAgentRepository.Permission.EDITOR)), null, null));
        assertTrue(shared.revision() > agent.revision(), "Relation-only changes advance the revision");
        assertThrows(ChatException.class, () -> personas.share(creator, agent.id(), agent.revision(), new ChatPersonaService.SharingInput(List.of(), List.of(), null, null)));

        var viewed = personas.get(viewer, agent.id());
        assertFalse(viewed.permissions().edit()); assertTrue(viewed.permissions().leave());
        assertEquals(List.of(sourceId), viewed.sourceIds(), "Viewers receive the full Onyx snapshot");
        assertEquals(1, viewed.groupShares().size());
        assertThrows(ChatException.class, () -> personas.update(viewer, agent.id(), shared.revision(),
                input("Viewer edit", List.of(), List.of(), true, null, null, null)));

        var edited = personas.update(groupEditor, agent.id(), shared.revision(), input("KPI tháng", List.of(), List.of(sourceId), true, null, null, null));
        assertEquals("KPI tháng", edited.name());
        // A non-owner editor cannot change Tenant-wide visibility; the rest of the share replacement applies.
        var editorShare = personas.share(groupEditor, agent.id(), edited.revision(), new ChatPersonaService.SharingInput(
                List.of(), List.of(new ChatPersonaService.GroupShareInput(editors, JdbcAgentRepository.Permission.EDITOR)), true,
                JdbcAgentRepository.Permission.EDITOR));
        assertFalse(editorShare.isPublic());
        assertThrows(ChatException.class, () -> personas.get(viewer, agent.id()));
        // The Group manager edits a private agent whose share Groups they all manage.
        assertTrue(personas.get(manager, agent.id()).permissions().edit());

        var published = personas.share(creator, agent.id(), editorShare.revision(), new ChatPersonaService.SharingInput(
                List.of(), List.of(new ChatPersonaService.GroupShareInput(editors, JdbcAgentRepository.Permission.VIEWER)), true,
                JdbcAgentRepository.Permission.VIEWER));
        assertTrue(published.isPublic());
        assertFalse(personas.get(stranger, agent.id()).permissions().edit());
        assertFalse(personas.get(manager, agent.id()).permissions().edit(), "Managers only edit private agents");
        var strangerSession = sessions.create(stranger, "Public agent");
        personas.select(stranger, strangerSession.id(), agent.id());
        assertEquals(List.of(sourceId), tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(strangerSession.id(), false, false)).options().sourceIds());
        assertTrue(personas.list(stranger, JdbcAgentRepository.View.SHARED, null, "kpi", 0, 30).stream().anyMatch(view -> view.id().equals(agent.id())));

        var privateAgain = personas.share(creator, agent.id(), published.revision(), new ChatPersonaService.SharingInput(List.of(), List.of(), false, null));
        assertThrows(ChatException.class, () -> tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(strangerSession.id(), false, false)),
                "Revoked use fails the next admission");

        var transferred = personas.transfer(creator, agent.id(), privateAgain.revision(), new ChatPersonaService.TransferInput(viewer.value(), null));
        assertEquals(viewer.value(), transferred.owner().actor().actorId());
        assertTrue(transferred.userShares().stream().anyMatch(share -> share.person().actorId().equals(creator.value())
                && share.permission() == JdbcAgentRepository.Permission.EDITOR), "The previous owner keeps editing access");
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
        assertTrue(personas.list(creator, JdbcAgentRepository.View.SHARED, null, null, 0, 30).stream()
                .anyMatch(view -> view.id().equals(agent.id())));
        assertTrue(personas.get(groupEditor, agent.id()).permissions().delete(), "AgentOwner Group members own the agent");
        personas.delete(groupEditor, agent.id(), personas.get(groupEditor, agent.id()).revision());
        assertThrows(ChatException.class, () -> personas.get(groupEditor, agent.id()));
        assertTrue(personas.restore(owner, agent.id()).deletedAt() == null);
    }

    @Test
    void featuredPublicAgentsSeedPinsOnceAndLabelsAreManaged() {
        var creator = member(tenant); var reader = member(tenant);
        when(authorization.effectiveCapabilities(creator)).thenReturn(Set.of(IamCapability.CHAT_WRITE, IamCapability.AGENTS_CREATE));
        when(authorization.effectiveCapabilities(reader)).thenReturn(Set.of(IamCapability.CHAT_READ, IamCapability.CHAT_WRITE));
        var label = personas.createLabel(reader, "Tài chính");
        assertThrows(ChatException.class, () -> personas.createLabel(creator, "tài chính"));
        assertThrows(ChatException.class, () -> personas.renameLabel(reader, label.id(), "Finance"));
        var agent = personas.create(creator, new ChatPersonaService.PersonaInput("Finance", "", "", "Always cite the report month.",
                List.of(), List.of(), null, Set.of("search"), null, null, null, null, List.of(), "chart", null, List.of(label.id()),
                false, false, java.time.Instant.parse("2026-01-01T00:00:00Z")));
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
        assertEquals("Always cite the report month.", settings.options().taskPrompt());
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z"), settings.options().knowledgeCutoff());
        assertEquals(Set.of("search"), settings.tools());
        assertFalse(settings.options().codeInterpreter(), "run_python follows the agent tool policy");
        assertEquals(List.of(), settings.mcpServerIds());
        assertFalse(settings.datetimeAware());
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
        assertEquals(List.of("tomtat", "kpi"), shortcuts.list(member, false).stream().map(JdbcPromptShortcutRepository.PromptShortcut::name).toList());
        shortcuts.hide(member, shared.id(), true);
        assertEquals(List.of("tomtat"), shortcuts.list(member, false).stream().map(JdbcPromptShortcutRepository.PromptShortcut::name).toList());
        assertTrue(shortcuts.list(member, true).stream().anyMatch(JdbcPromptShortcutRepository.PromptShortcut::hidden));
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

    @Test
    void orderedMessageFilesSurviveReplayRegenerationEditingAndSharedHistoryWithoutNewUploads() {
        var a = readyFile(owner); var b = readyFile(owner); var foreign = readyFile(other);
        var session = sessions.create(owner, "Files");
        var denied = new ChatCommand(ChatCommand.Operation.SEND, session.rootMessageId(), UUID.randomUUID(), "", null, List.of(foreign));
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(), denied, Duration.ofMinutes(2), 32000, null));
        assertTrue(sessions.history(owner, session.id(), null, 100).isEmpty());
        var command = new ChatCommand(ChatCommand.Operation.SEND, session.rootMessageId(), UUID.randomUUID(), "", null, List.of(b, a));
        var first = turns.reserve(owner, session.id(), command, Duration.ofMinutes(2), 32000, null);
        var replay = turns.reserve(owner, session.id(), command, Duration.ofMinutes(2), 32000, null);
        assertFalse(replay.created());
        assertEquals(first.assistantMessageId(), replay.assistantMessageId());
        assertEquals(List.of(b, a), turns.loadContext(owner, session.id(), replay).newestFirst().getFirst().files().stream().map(ChatFileDescriptor::id).toList());
        assertThrows(ChatException.class, () -> turns.reserve(owner, session.id(), new ChatCommand(command.operation(),
                command.targetMessageId(), command.requestId(), "", null, List.of(a, b)), Duration.ofMinutes(2), 32000, null));
        turns.finish(session.id(), first.assistantMessageId(), ChatMessage.Status.COMPLETED, "File answer");
        var regenerated = turns.reserve(owner, session.id(), new ChatCommand(ChatCommand.Operation.REGENERATE,
                first.userMessageId(), UUID.randomUUID(), "", null), Duration.ofMinutes(2), 32000, null);
        assertEquals(first.userMessageId(), regenerated.userMessageId());
        assertNotNull(regenerated.context());
        assertEquals(List.of(b, a), regenerated.context().newestFirst().getFirst().files().stream().map(ChatFileDescriptor::id).toList());
        turns.finish(session.id(), regenerated.assistantMessageId(), ChatMessage.Status.COMPLETED, "Regenerated answer");
        var edited = turns.reserve(owner, session.id(), new ChatCommand(ChatCommand.Operation.EDIT,
                first.userMessageId(), UUID.randomUUID(), "", null, List.of(a)), Duration.ofMinutes(2), 32000, null);
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
        var persona = personas.create(owner, input("No files", List.of(), List.of(), false, null, null, List.of()));
        personas.select(owner, session.id(), persona.id());
        var second = reserve(session, first.assistantMessageId(), UUID.randomUUID(), "Custom assistant");
        assertNotNull(second.context());
        assertTrue(second.context().workspaceFiles().isEmpty());
        turns.finish(session.id(), second.assistantMessageId(), ChatMessage.Status.COMPLETED, "No project files");
        var settings = input("With files", List.of(), List.of(), false, null, null, List.of(file));
        personas.update(owner, persona.id(), persona.revision(), settings);
        assertThrows(ChatException.class, () -> personas.update(owner, persona.id(), persona.revision(), settings));
        var third = reserve(session, second.assistantMessageId(), UUID.randomUUID(), "Read custom files");
        assertNotNull(third.context());
        assertEquals(List.of(file), third.context().workspaceFiles().stream().map(ChatFileDescriptor::id).toList());
        assertTrue(second.context().workspaceFiles().isEmpty());
    }

    private static ChatPersonaService.PersonaInput input(String name, List<String> starters, List<UUID> sources, boolean search,
            Integer context, Integer output, List<UUID> files) {
        return input(name, "", starters, sources, search, context, output, files);
    }

    private static ChatPersonaService.PersonaInput input(String name, String instructions, List<String> starters, List<UUID> sources,
            boolean search, Integer context, Integer output, List<UUID> files) {
        return new ChatPersonaService.PersonaInput(name, "", instructions, null, starters, sources, null,
                search ? Set.of("search") : Set.of(), null, null, context, output, files, null, null, null, null, null, null);
    }

    private UUID readyFile(ActorId actor) {
        return java.util.Objects.requireNonNull(tx.execute(ignored -> {
            var tenantId = new TenantId(tenant);
            var objectId = new io.memoryos.objectstorage.StoredObjectId(UUID.randomUUID());
            var uploadId = new io.memoryos.objectstorage.ObjectUploadId(UUID.randomUUID());
            var spec = new io.memoryos.objectstorage.ObjectUploadSpecification("Ghi chú.txt", "text/plain", 4,
                    new io.memoryos.objectstorage.ContentSha256("a".repeat(64)), io.memoryos.objectstorage.ObjectUploadPurpose.CHAT_FILE);
            new io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository(jdbc).create(tenantId, objectId,
                    new io.memoryos.objectstorage.ObjectKey("raw/" + tenant + "/" + objectId.value()), spec, java.time.Instant.now().plusSeconds(600));
            new io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository(jdbc).create(tenantId, uploadId, objectId, spec.purpose());
            var id = new io.memoryos.chat.persistence.JdbcUserFileRepository(jdbc).create(tenantId, actor, UUID.randomUUID(), uploadId, spec);
            // This suite tests message transactions; worker/adoption publication is exercised separately.
            jdbc.sql("UPDATE chat_user_file SET status='READY',plaintext='Test',detected_media_type='text/plain' WHERE id=:id")
                    .param("id", id).update();
            return id;
        }));
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
