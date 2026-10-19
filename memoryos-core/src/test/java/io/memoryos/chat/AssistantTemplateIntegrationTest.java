package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.BusinessException;
import io.memoryos.TestDatabase;
import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelSettings;
import io.memoryos.chat.files.ChatFileAttachments;
import io.memoryos.chat.files.persistence.JdbcChatFileAttachmentRepository;
import io.memoryos.chat.persona.AssistantTemplateProvisioner;
import io.memoryos.chat.persona.persistence.JdbcAgentModelRepository;
import io.memoryos.chat.persona.persistence.JdbcAgentRepository;
import io.memoryos.chat.persona.persistence.JdbcDocumentSetRepository;
import io.memoryos.chat.persona.persistence.JpaPersonaRepository;
import io.memoryos.chat.persona.persistence.PersonaRevisions;
import io.memoryos.chat.session.DefaultChatSessionService;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.session.persistence.JdbcChatSearchRepository;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.ActorLanguageService;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.persistence.ActorRefreshImpl;
import io.memoryos.iam.identity.persistence.JpaActorRepository;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.library.LibraryStorageProperties;
import io.memoryos.library.LibraryTrashProperties;
import io.memoryos.library.StorageQuotaService;
import io.memoryos.library.UserFileContentService;
import io.memoryos.library.UserFileProperties;
import io.memoryos.library.UserFileService;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MemoryOS's assistant templates (MEM-281) on PostgreSQL: provisioning writes only what differs, a template is used
 * as it is and never changed in the Tenant, an administrator only shows or hides it, and "Tạo bản riêng" makes a
 * private copy.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class AssistantTemplateIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private TransactionTemplate tx;
    private AssistantTemplateProvisioner provisioner;
    private ChatPersonaService personas;
    private ChatSessionService sessions;
    private IamAuthorization authorization;
    private UUID tenant;
    private ActorId admin;
    private ActorId member;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        tx = new TransactionTemplate(jpa.transactionManager());
        var tenants = TestDatabase.transactionalProxy(new JpaTenantAccessResolver(
                        new JpaTenantRepository(jpa.entityManager()), new IamLockRepository(jdbc)),
                TenantAccessResolver.class, jpa.transactionManager());
        var repository = new JdbcChatRepository(jdbc);
        authorization = mock(IamAuthorization.class);
        sessions = TestDatabase.transactionalProxy(new DefaultChatSessionService(tenants, authorization, repository,
                new JdbcChatSearchRepository(jdbc)), ChatSessionService.class, jpa.transactionManager());
        var files = new UserFileService(tenants, new JdbcUserFileRepository(jdbc),
                new ChatFileAttachments(new JdbcChatFileAttachmentRepository(jdbc)), mock(ObjectUploadService.class),
                new UserFileProperties(104857600, 262144000),
                new StorageQuotaService(tenants, new LibraryStorageProperties(0), new JdbcLibraryRepository(jdbc)),
                new LibraryTrashProperties(Duration.ZERO), jpa.transactionManager());
        var models = mock(ChatModelAccess.class);
        when(models.availableModelsForPersona(any(), any())).thenReturn(List.of(new ModelCatalogService.AvailableModel(
                UUID.randomUUID(), UUID.randomUUID(), "Provider", "model", "Model",
                new ModelSettings.Capabilities(true, true, false, false, false), 32000, 4096, null, true)));
        var sources = mock(SourceSearchService.class);
        when(sources.scope(any())).thenAnswer(call -> new SourceSearchScope(new TenantId(tenant), call.getArgument(0), Map.of()));
        var agents = new JdbcAgentRepository(jdbc);
        var documentSetRows = new JdbcDocumentSetRepository(jdbc);
        var documentSets = service(new DocumentSetService(tenants, authorization, sources, agents, documentSetRows));
        var languages = new ActorLanguageService(jpa.repository(JpaActorRepository.class,
                RepositoryFragments.just(new ActorRefreshImpl(jpa.entityManager()))), tenants);
        var revisions = new PersonaRevisions(jpa.entityManager());
        personas = service(new ChatPersonaService(tenants, authorization, repository,
                jpa.repository(JpaPersonaRepository.class), agents, revisions, models, sources, documentSets,
                documentSetRows, files, mock(UserFileContentService.class), languages));
        provisioner = new AssistantTemplateProvisioner(jpa.repository(JpaPersonaRepository.class), agents, revisions);

        tenant = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Chat', 'ACTIVE', 'test')")
                .param("id", tenant).param("slug", tenant.toString()).update();
        var defaults = new PersonaProperties();
        repository.provisionPersona(new TenantId(tenant), defaults.getName(), defaults.getInstructions());
        admin = member();
        member = member();
        when(authorization.effectiveCapabilities(admin)).thenReturn(Set.of(IamCapability.AGENTS_MANAGE, IamCapability.AGENTS_CREATE));
        when(authorization.effectiveCapabilities(member)).thenReturn(Set.of(IamCapability.AGENTS_CREATE));
        provision(AssistantTemplates.all());
    }

    @AfterEach
    void tearDown() {
        try { if (jpa != null) jpa.close(); }
        finally { if (dataSource != null) dataSource.close(); }
    }

    @Test
    void provisioningWritesEachTemplateOnceAndRewritesOnlyWhatChanged() {
        var rows = templateRows();
        assertEquals(Set.copyOf(AssistantTemplates.KEYS), rows.keySet());
        UUID policy = rows.get("policy-qa");
        assertEquals(Set.of("search"), tools(policy));
        assertEquals(Set.of(), tools(rows.get("translate-vi-en")));
        assertTrue(jdbc.sql("SELECT grounded FROM persona WHERE id = :id").param("id", policy).query(Boolean.class).single());
        var revisions = revisionsByKey();

        provision(AssistantTemplates.all());
        assertEquals(revisions, revisionsByKey(), "A start without template changes writes nothing");

        // An administrator hid one; the next release changes its wording and drops another template.
        jdbc.sql("UPDATE persona SET is_listed = FALSE WHERE id = :id").param("id", policy).update();
        var policyTemplate = AssistantTemplates.find("policy-qa").orElseThrow();
        var reworded = new AssistantTemplate(policyTemplate.key(), policyTemplate.avatarSeed(),
                Set.of("search", "web_search"), true,
                new AssistantTemplate.Text("Hỏi đáp quy định", policyTemplate.vi().title(), policyTemplate.vi().description(),
                        policyTemplate.vi().greeting(), policyTemplate.vi().starters()),
                policyTemplate.en(), policyTemplate.instructions());
        var next = AssistantTemplates.all().stream().filter(template -> !template.key().equals("okr-kpi"))
                .map(template -> template.key().equals("policy-qa") ? reworded : template).toList();
        provision(next);

        var after = revisionsByKey();
        assertNotEquals(revisions.get("policy-qa"), after.get("policy-qa"));
        assertEquals(revisions.get("email"), after.get("email"), "An unchanged template is not written");
        assertFalse(after.containsKey("okr-kpi"), "A template the catalogue dropped is deleted");
        assertEquals("Hỏi đáp quy định", jdbc.sql("SELECT name FROM persona WHERE id = :id").param("id", policy).query(String.class).single());
        assertEquals(Set.of("search", "web_search"), tools(policy));
        assertFalse(jdbc.sql("SELECT is_listed FROM persona WHERE id = :id").param("id", policy).query(Boolean.class).single(),
                "The administrator's choice to hide it is kept");

        provision(AssistantTemplates.all());
        assertEquals(rows.get("okr-kpi"), templateRows().get("okr-kpi"), "A template that comes back restores its row");
    }

    @Test
    void aMemberUsesATemplateAsItIsInTheirLanguageAndCannotChangeIt() {
        UUID email = templateRows().get("email");
        jdbc.sql("UPDATE actors SET ui_language = 'en' WHERE id = :id").param("id", member.value()).update();

        var listed = personas.list(member, AgentListFilter.ALL, null, null, 0, 30);
        assertTrue(listed.getFirst().builtin(), "The built-in assistant comes first");
        var templates = listed.stream().filter(view -> view.templateKey() != null).toList();
        assertEquals(AssistantTemplates.KEYS.size(), templates.size(), "Every template is on the first page");
        var view = personas.get(member, email);
        assertEquals("email", view.templateKey());
        assertEquals("Work email writer", view.name());
        assertEquals(AssistantTemplates.find("email").orElseThrow().en().starters(), view.starterPrompts());
        assertTrue(tx.execute(ignored -> new JdbcAgentModelRepository(jdbc).personas(tenant, member.value(), false, null, 100))
                        .stream().anyMatch(summary -> summary.id().equals(email) && summary.name().equals("Work email writer")),
                "The model page names it in the reader's language too");
        assertEquals(new PersonaPermissions(false, false, false, false, false, false, false), view.permissions(),
                "Nothing to edit, share, delete or transfer; a bot binding needs edit rights too");
        assertEquals(List.of(), personas.list(member, AgentListFilter.SHARED, null, null, 0, 30).stream()
                .filter(shared -> shared.templateKey() != null).toList());

        var input = new ChatPersonaService.PersonaInput("Mine now", null, "", "Write badly.", null, List.of(), List.of(),
                null, null, null, null, null, null, null, null, null, null, null);
        assertUnavailable(() -> personas.update(member, email, view.revision(), input));
        assertUnavailable(() -> personas.update(admin, email, view.revision(), input));
        assertUnavailable(() -> personas.delete(admin, email, view.revision()));
        assertUnavailable(() -> personas.share(admin, email, view.revision(),
                new ChatPersonaService.SharingInput(List.of(), List.of(), true, AgentPermission.EDITOR)));
        assertUnavailable(() -> personas.transfer(admin, email, view.revision(),
                new ChatPersonaService.TransferInput(member.value(), null)));
        assertUnavailable(() -> personas.reorder(admin, List.of(email)));
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(BusinessException.class, () -> personas.listing(admin, email,
                view.revision(), new ChatPersonaService.ListingInput(true, true, null))).code());
    }

    @Test
    void theListLeadsWithTheBuiltInAssistantThenFeaturedOnesThenTheTemplates() {
        var featured = everyones("Quy trình thanh toán", true);
        var plain = everyones("Ánh sáng", false);

        var listed = personas.list(member, AgentListFilter.ALL, null, null, 0, 30);

        assertTrue(listed.get(0).builtin());
        assertEquals(featured.id(), listed.get(1).id());
        assertTrue(listed.subList(2, 2 + AssistantTemplates.KEYS.size()).stream().allMatch(view -> view.templateKey() != null));
        assertEquals(plain.id(), listed.getLast().id());
    }

    @Test
    void aConversationWithATemplateTurnsWithItsInstructionsAndEndsWhenItIsHidden() {
        UUID policy = templateRows().get("policy-qa");
        var session = sessions.create(member, "Nghỉ phép");
        personas.select(member, session.id(), policy);

        var turn = tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(session.id(), false, false));
        assertTrue(turn.instructions().contains(AssistantTemplates.find("policy-qa").orElseThrow().instructions()));
        assertTrue(turn.options().searchEnabled());
        assertTrue(turn.options().grounded());
        assertFalse(turn.options().sourcesRestricted(), "A template searches everything the person may read");
        assertEquals(List.of(policy), personas.replacePins(member, List.of(policy)).stream().map(ChatPersonaService.PersonaView::id).toList());
        assertTrue(personas.assistants(member).stream().anyMatch(row -> row.id().equals(policy) && row.usable()));

        var hidden = personas.listing(admin, policy, personas.get(admin, policy).revision(),
                new ChatPersonaService.ListingInput(false, false, null));
        assertFalse(hidden.listed());
        assertTrue(personas.list(member, AgentListFilter.ALL, null, null, 0, 30).stream().noneMatch(view -> view.id().equals(policy)));
        assertUnavailable(() -> personas.get(member, policy));
        assertEquals(List.of(), personas.pins(member), "A hidden template leaves the pins");
        assertTrue(personas.assistants(member).stream().anyMatch(row -> row.id().equals(policy) && !row.usable()),
                "Its sidebar row stays, marked unusable, as for any assistant no longer shared");
        assertThrows(BusinessException.class,
                () -> tx.execute(ignored -> new JdbcChatRepository(jdbc).persona(session.id(), false, false)),
                "The conversation cannot continue while the template is hidden");
        assertEquals(policy, personas.get(admin, policy).id(), "The administrator still opens it to show it again");
        assertTrue(personas.list(admin, AgentListFilter.ALL, null, null, 0, 30).stream()
                        .anyMatch(view -> view.id().equals(policy) && !view.listed()),
                "and finds it, hidden, in the list");

        personas.listing(admin, policy, hidden.revision(), new ChatPersonaService.ListingInput(true, false, null));
        assertEquals(policy, personas.get(member, policy).id());
    }

    @Test
    void aCopyIsTheCallersOwnPrivateAssistantInTheirLanguage() {
        UUID contract = templateRows().get("contract-review");
        var template = AssistantTemplates.find("contract-review").orElseThrow();

        var copy = personas.copy(member, contract);

        assertNull(copy.templateKey());
        assertEquals(template.vi().name(), copy.name());
        assertEquals(template.vi().starters(), copy.starterPrompts());
        assertEquals(template.instructions(), copy.instructions());
        assertEquals(template.tools(), copy.tools());
        assertEquals(List.of(), copy.sourceIds());
        assertEquals(member.value(), copy.owner().actor().actorId());
        assertFalse(copy.isPublic());
        assertNotEquals(template.avatarSeed(), copy.avatarSeed());
        assertTrue(copy.permissions().edit());
        var colleague = member();
        when(authorization.effectiveCapabilities(colleague)).thenReturn(Set.of());
        assertUnavailable(() -> personas.get(colleague, copy.id()));

        jdbc.sql("UPDATE actors SET ui_language = 'en' WHERE id = :id").param("id", member.value()).update();
        assertEquals(template.en().name(), personas.copy(member, contract).name());
        assertEquals("AGENT_NOT_A_TEMPLATE", assertThrows(BusinessException.class, () -> personas.copy(member, copy.id())).code());
    }

    /** An administrator's assistant shared with the organization and listed, featured or not. */
    private ChatPersonaService.PersonaView everyones(String name, boolean featured) {
        var created = personas.create(admin, new ChatPersonaService.PersonaInput(name, null, "", "", null,
                List.of(), List.of(), null, null, null, null, null, null, null, null, null, null, null));
        var shared = personas.share(admin, created.id(), created.revision(),
                new ChatPersonaService.SharingInput(List.of(), List.of(), true, AgentPermission.VIEWER));
        return personas.listing(admin, created.id(), shared.revision(), new ChatPersonaService.ListingInput(true, featured, null));
    }

    private void assertUnavailable(Runnable call) {
        assertEquals("CHAT_UNAVAILABLE", assertThrows(BusinessException.class, call::run).code());
    }

    private void provision(List<AssistantTemplate> catalogue) {
        tx.executeWithoutResult(ignored -> provisioner.provision(new TenantId(tenant), catalogue));
    }

    private Map<String, UUID> templateRows() {
        var rows = new HashMap<String, UUID>();
        jdbc.sql("SELECT template_key, id FROM persona WHERE tenant_id = :tenant AND template_key IS NOT NULL AND deleted_at IS NULL")
                .param("tenant", tenant).query((row, ignored) -> rows.put(row.getString(1), row.getObject(2, UUID.class))).list();
        return rows;
    }

    private Map<String, Long> revisionsByKey() {
        var revisions = new HashMap<String, Long>();
        jdbc.sql("SELECT template_key, revision FROM persona WHERE tenant_id = :tenant AND template_key IS NOT NULL AND deleted_at IS NULL")
                .param("tenant", tenant).query((row, ignored) -> revisions.put(row.getString(1), row.getLong(2))).list();
        return revisions;
    }

    private Set<String> tools(UUID persona) {
        return Set.copyOf(jdbc.sql("SELECT tool_key FROM persona_tool WHERE persona_id = :id").param("id", persona)
                .query(String.class).list());
    }

    private ActorId member() {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant).param("actor", actor.value()).update();
        return actor;
    }

    @SuppressWarnings("unchecked")
    private <T> T service(T target) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(jpa.transactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }
}
