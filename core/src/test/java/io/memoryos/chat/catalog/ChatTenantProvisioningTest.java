package io.memoryos.chat.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatPersonaService;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.catalog.openai.OpenAiChatProviderAdapter;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JpaChatModelDefaultRepository;
import io.memoryos.chat.persistence.JpaLlmProviderRepository;
import io.memoryos.chat.persistence.JpaModelConfigurationRepository;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import io.memoryos.iam.group.DefaultGroupProvisioner;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.GroupScopeService;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.ExternalIdentity;
import io.memoryos.iam.identity.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.bootstrap.DefaultInitialTenantBootstrapper;
import io.memoryos.iam.tenant.bootstrap.InitialTenantBootstrapRequest;
import io.memoryos.iam.tenant.bootstrap.InitialTenantBootstrapper;
import io.memoryos.iam.tenant.persistence.JpaTenantAccessResolver;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chat defaults are provisioned by the Tenant bootstrap event, in the bootstrap transaction, through Spring's real
 * {@code @EventListener} dispatch; Chat reads then run in read-only transactions without writing anything.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatTenantProvisioningTest {
    private static final TenantId TENANT = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000031"));
    private static final ModelSettings SETTINGS = new ModelSettings(400000, 128000,
            new ModelSettings.Capabilities(true, true, true, true), Map.of("maxCompletionTokens", true), null, "openai-o200k-v1");

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private ModelCatalogRepository catalog;
    private ChatProviderAdapters adapters;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        catalog = new ModelCatalogRepository(jdbc, jpa.repository(JpaLlmProviderRepository.class),
                jpa.repository(JpaModelConfigurationRepository.class), jpa.repository(JpaChatModelDefaultRepository.class));
        adapters = new ChatProviderAdapters(List.of(new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, new SimpleMeterRegistry())));
    }

    @AfterEach
    void tearDown() {
        try {
            if (context != null) context.close();
            if (jpa != null) jpa.close();
        } finally {
            if (dataSource != null) dataSource.close();
        }
    }

    @Test
    void creatingTheTenantProvisionsItsChatDefaultsInTheSameTransaction() {
        var result = bootstrapper(listening("https://api.openai.com/v1")).bootstrap(request());

        assertTrue(result.created());
        assertProvisioned();
    }

    @Test
    void aFailedProvisioningLeavesNoTenantBehind() {
        var bootstrapper = bootstrapper(listening("ftp://api.openai.com/v1"));

        assertThrows(ChatException.class, () -> bootstrapper.bootstrap(request()));

        assertEquals(0L, count("tenants"));
        assertEquals(0L, count("chat_model_default"));
        assertEquals(0L, count("persona"));
    }

    @Test
    void restartProvisionsATenantCreatedBeforeChatListenedAndKeepsLaterChanges() {
        bootstrapper(event -> { }).bootstrap(request());
        assertEquals(1L, count("tenants"));
        assertEquals(0L, count("chat_model_default"));
        assertEquals(0L, count("persona"));

        var restarted = bootstrapper(listening("https://api.openai.com/v1"));
        assertFalse(restarted.bootstrap(request()).created());
        assertProvisioned();

        jdbc.sql("UPDATE persona SET name = 'Trợ lý Tasco' WHERE builtin_key = 'default'").update();
        jdbc.sql("UPDATE model_flow_default SET model_configuration_id = NULL, revision = revision + 1").update();
        restarted.bootstrap(request());

        assertCatalogProvisioned();
        assertAgentProvisioned();
        assertEquals("Trợ lý Tasco", jdbc.sql("SELECT name FROM persona WHERE builtin_key = 'default'").query(String.class).single());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM model_flow_default WHERE model_configuration_id IS NOT NULL")
                .query(Long.class).single());
    }

    @Test
    void chatReadsRunReadOnlyOnAProvisionedTenant() {
        ActorId owner = bootstrapper(listening("https://api.openai.com/v1")).bootstrap(request()).ownerActorId();
        var readOnly = new TransactionTemplate(jpa.transactionManager());
        readOnly.setReadOnly(true);
        // The harness enforces read-only transactions, so any write below would fail rather than pass silently.
        assertThrows(RuntimeException.class, () -> readOnly.executeWithoutResult(_ ->
                jdbc.sql("UPDATE chat_model_default SET revision = revision + 1").update()));
        var locks = new IamLockRepository(jdbc);
        var service = TestDatabase.transactionalProxy(new ModelCatalogService(catalog, new JdbcChatRepository(jdbc),
                new JpaTenantAccessResolver(new JpaTenantRepository(jpa.entityManager()), locks),
                new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks), adapters,
                new ProviderCredentials("", "sk-deployment"), mock(GroupScopeService.class), TestDatabase.noAudit()),
                ModelCatalogService.class, jpa.transactionManager());
        long revision = jdbc.sql("SELECT revision FROM chat_model_default").query(Long.class).single();

        readOnly.executeWithoutResult(_ -> {
            var providers = service.providers(owner);
            assertEquals(1, providers.size());
            assertEquals(1, service.models(owner, providers.getFirst().id()).size());
            assertNotNull(service.defaultModel(owner).modelConfigurationId());
            assertEquals(ModelFlow.values().length, service.flowDefaults(owner).size());
            assertEquals(1, service.personas(owner, null, 25).items().size());
            var available = service.availableModels(owner, null);
            assertEquals(1, available.size());
            assertTrue(available.getFirst().isDefault());
            assertDoesNotThrow(() -> service.availableWebModels(owner, null));
            assertDoesNotThrow(() -> service.validationSelection(owner, available.getFirst().id()));
        });

        assertEquals(revision, jdbc.sql("SELECT revision FROM chat_model_default").query(Long.class).single());
        assertProvisioned();
    }

    private UUID assertCatalogProvisioned() {
        assertEquals(1L, count("chat_model_default"));
        var provider = jdbc.sql("SELECT id, base_url, builtin_key FROM llm_provider").query().singleRow();
        assertEquals("https://api.openai.com/v1", provider.get("base_url"));
        assertEquals("deployment", provider.get("builtin_key"));
        UUID model = jdbc.sql("SELECT id FROM model_configuration WHERE model_name = 'gpt-5.1'").query(UUID.class).single();
        assertEquals(model, jdbc.sql("SELECT model_configuration_id FROM chat_model_default WHERE tenant_id = :tenant")
                .param("tenant", TENANT.value()).query(UUID.class).single());
        assertEquals((long) ModelFlow.values().length, count("model_flow_default"));
        return model;
    }

    private void assertProvisioned() {
        UUID model = assertCatalogProvisioned();
        // Each task names the Chat default from the start, as the lazy path seeded it.
        assertEquals((long) ModelFlow.values().length, jdbc.sql("SELECT count(*) FROM model_flow_default WHERE model_configuration_id = :model")
                .param("model", model).query(Long.class).single());
        assertAgentProvisioned();
    }

    private void assertAgentProvisioned() {
        UUID persona = jdbc.sql("SELECT id FROM persona WHERE tenant_id = :tenant AND builtin_key = 'default'")
                .param("tenant", TENANT.value()).query(UUID.class).single();
        assertEquals((long) ChatPersonaService.TOOLS.size(), jdbc.sql("SELECT count(*) FROM persona_tool WHERE persona_id = :persona")
                .param("persona", persona).query(Long.class).single());
        assertEquals(1L, count("persona"));
    }

    /** A Spring context whose event multicaster invokes the provisioner through its {@code @EventListener}. */
    private ApplicationEventPublisher listening(String baseUrl) {
        var provisioner = TestDatabase.transactionalProxy(new ChatTenantProvisioner(catalog, new JdbcChatRepository(jdbc), adapters,
                new PersonaProperties(), new ModelCatalogService.Deployment(baseUrl, "gpt-5.1", SETTINGS)),
                ChatTenantProvisioner.class, jpa.transactionManager());
        if (context != null) context.close();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(ChatTenantProvisioner.class, () -> provisioner);
        context.refresh();
        return context;
    }

    private InitialTenantBootstrapper bootstrapper(ApplicationEventPublisher events) {
        var tenants = new JpaTenantRepository(jpa.entityManager());
        var identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        var groups = new DefaultGroupProvisioner(new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()), new GroupCapabilityGrantRepository(jpa.entityManager()));
        return TestDatabase.transactionalProxy(new DefaultInitialTenantBootstrapper(tenants, new IamLockRepository(jdbc),
                identities, identities, groups, events), InitialTenantBootstrapper.class, jpa.transactionManager());
    }

    private static InitialTenantBootstrapRequest request() {
        return new InitialTenantBootstrapRequest(TENANT,
                new ExternalIdentity("https://keycloak.example/realms/memoryos", "chat-owner"), "tasco", "Tasco AI", "TEST-CHAT-PROVISION");
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
