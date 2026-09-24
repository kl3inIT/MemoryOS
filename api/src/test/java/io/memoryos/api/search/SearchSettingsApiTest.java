package io.memoryos.api.search;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.api.ApiPostgresDatabase;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.memoryos.retrieval.settings.EmbeddingProbe;
import io.memoryos.retrieval.settings.SearchGenerations;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-135: every Search settings endpoint requires model management of the operating Tenant, and each answers with
 * the status the contract gives it. OpenSearch and the embedding endpoint are replaced; the rebuild lifecycle itself
 * is covered by {@code SearchRebuildIntegrationTest}.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.owner-subject=search-settings-owner",
        "memoryos.initial-tenant.slug=search-settings",
        "memoryos.initial-tenant.display-name=Search settings",
        "memoryos.initial-tenant.change-reference=MEM-135-TEST",
        "memoryos.search.endpoint=http://127.0.0.1:1",
        "memoryos.search.provider-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class SearchSettingsApiTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER = "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();
    private static final UUID MEMBER = UUID.fromString("7c1e4b2a-9d3f-4a6b-8e5c-2f1a0b9c8d7e");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private SearchGenerations generations;
    @MockitoBean private OpenSearchIndexService index;
    @MockitoBean private EmbeddingProbe probe;
    @MockitoSpyBean private TenantAccessResolver tenants;

    private ActorAuthenticationToken owner;
    private ActorAuthenticationToken member;
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        ApiPostgresDatabase.configure(registry);
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
        registry.add("memoryos.identity.keycloak.admin.client-secret", () -> "test-provisioner-secret");
        registry.add("memoryos.identity.keycloak.admin.action-redirect-uri", () -> "http://127.0.0.1/invite/activate");
    }

    @AfterAll
    static void stopIdentityServer() { IDENTITY_SERVER.stop(0); }

    @BeforeEach
    void actors() {
        reset(tenants, index, probe);
        UUID tenant = jdbc.sql("SELECT id FROM tenants WHERE slug='search-settings'").query(UUID.class).single();
        jdbc.sql("INSERT INTO actors(id) VALUES(:id) ON CONFLICT DO NOTHING").param("id", MEMBER).update();
        jdbc.sql("""
                INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("actor", MEMBER).update();
        jdbc.sql("""
                INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id,is_manager)
                SELECT tenant_id,id,:actor,FALSE FROM iam_groups WHERE tenant_id=:tenant AND system_key='BASIC'
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("actor", MEMBER).update();
        UUID ownerId = jdbc.sql("""
                SELECT actor_id FROM external_identity_bindings WHERE issuer='https://issuer.example.test' AND subject='search-settings-owner'
                """).query(UUID.class).single();
        owner = new ActorAuthenticationToken(new IdentityContext(new ActorId(ownerId)));
        member = new ActorAuthenticationToken(new IdentityContext(new ActorId(MEMBER)));
        generations.present(); // seeds PRESENT, as the first api start does
        when(index.indexExists(anyString())).thenReturn(true);
        when(index.deleteIndex(anyString())).thenReturn(true);
        when(probe.probe(anyString(), anyString(), anyString(), any()))
                .thenReturn(new EmbeddingProbe.Outcome(true, "Qwen/Qwen3-Embedding-0.6B", 1024, 12, null, null));
    }

    @Test
    void everyOperationRequiresModelManagementOfTheOperatingTenant() throws Exception {
        UUID someone = UUID.randomUUID();
        var requests = new MockHttpServletRequestBuilder[] {
                get("/api/search/settings"),
                post("/api/search/settings/future").content(generation(someone)),
                delete("/api/search/settings/future"),
                post("/api/search/settings/future/switch"),
                post("/api/search/settings/past/" + someone + "/restore"),
                get("/api/search/embedding-providers"),
                post("/api/search/embedding-providers").content(provider("Denied", "key", null)),
                put("/api/search/embedding-providers/" + someone).content(provider("Denied", null, 1L)),
                delete("/api/search/embedding-providers/" + someone),
                post("/api/search/embedding-providers/test").content(test(someone)),
                get("/api/search/embedding-models"),
        };
        for (var request : requests) {
            mockMvc.perform(write(request).with(authentication(member))).andExpect(status().isForbidden());
        }
        // A model manager whose Tenant is not the operating one: the index is shared, so the settings are not theirs.
        doReturn(Optional.of(new TenantId(UUID.randomUUID()))).when(tenants).operatingTenant();
        for (var request : requests) {
            mockMvc.perform(write(request).with(authentication(owner))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/search/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void providersAreCreatedReplacedAtTheirRevisionAndDeletedUnlessInUse() throws Exception {
        mockMvc.perform(post("/api/search/embedding-providers").with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(provider("No CSRF", "key", null)))
                .andExpect(status().isForbidden());
        var created = mockMvc.perform(write(post("/api/search/embedding-providers").content(provider("serving-embedding", "tei-key", null)))
                        .with(authentication(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasApiKey").value(true))
                .andExpect(jsonPath("$.inUse").value(false))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).path("id").asString();
        mockMvc.perform(write(post("/api/search/embedding-providers").content(provider("serving-embedding", null, null)))
                .with(authentication(owner))).andExpect(status().isConflict());
        mockMvc.perform(write(put("/api/search/embedding-providers/" + id).content(provider("serving-embedding", null, 7L)))
                        .with(authentication(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEARCH_SETTINGS_STALE_REVISION"));
        mockMvc.perform(write(put("/api/search/embedding-providers/" + id).content(provider("serving-embedding", "", 1L)))
                        .with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.hasApiKey").value(false));
        mockMvc.perform(write(post("/api/search/embedding-providers").content("""
                        {"name":"Bad","endpoint":"http://user:secret@host/v1","apiKey":null,"dataBoundary":"INTERNAL","revision":null}
                        """)).with(authentication(owner)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEARCH_SETTINGS_INVALID"));

        var providers = mockMvc.perform(get("/api/search/embedding-providers").with(authentication(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String seeded = null;
        for (var provider : json.readTree(providers)) if (provider.path("inUse").asBoolean()) seeded = provider.path("id").asString();
        mockMvc.perform(write(delete("/api/search/embedding-providers/" + seeded)).with(authentication(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEARCH_SETTINGS_PROVIDER_IN_USE"));
        mockMvc.perform(write(delete("/api/search/embedding-providers/" + id)).with(authentication(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(write(delete("/api/search/embedding-providers/" + id)).with(authentication(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aStoredKeyIsNeverSentToAnEndpointItWasNotSavedWith() throws Exception {
        String saved = "http://172.24.244.79:18090/v1";
        String other = "http://10.9.9.9:8080/v1";
        var created = mockMvc.perform(write(post("/api/search/embedding-providers")
                        .content(provider("moved-" + UUID.randomUUID(), saved, "tei-key", null))).with(authentication(owner)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String name = json.readTree(created).path("name").asString();
        UUID id = UUID.fromString(json.readTree(created).path("id").asString());

        // Moving the endpoint while keeping the stored key is refused, and nothing changes.
        mockMvc.perform(write(put("/api/search/embedding-providers/" + id).content(provider(name, other, null, 1L)))
                        .with(authentication(owner)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEARCH_SETTINGS_INVALID"));
        // A check of the saved provider at another endpoint needs the key in the request.
        mockMvc.perform(write(post("/api/search/embedding-providers/test").content(test(id, other, null))).with(authentication(owner)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEARCH_SETTINGS_INVALID"));
        verify(probe, never()).probe(eq(other), anyString(), anyString(), any());
        mockMvc.perform(write(post("/api/search/embedding-providers/test").content(test(id, saved, null))).with(authentication(owner)))
                .andExpect(status().isOk());
        verify(probe).probe(eq(saved), eq("tei-key"), anyString(), any());
        mockMvc.perform(write(post("/api/search/embedding-providers/test").content(test(id, other, "other-key"))).with(authentication(owner)))
                .andExpect(status().isOk());
        verify(probe).probe(eq(other), eq("other-key"), anyString(), any());

        // A new key, or removing the key, moves it.
        mockMvc.perform(write(put("/api/search/embedding-providers/" + id).content(provider(name, other, "other-key", 1L)))
                        .with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endpoint").value(other))
                .andExpect(jsonPath("$.hasApiKey").value(true)).andExpect(jsonPath("$.revision").value(2));
        mockMvc.perform(write(put("/api/search/embedding-providers/" + id).content(provider(name, saved, "", 2L)))
                        .with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endpoint").value(saved))
                .andExpect(jsonPath("$.hasApiKey").value(false));
        // Without a stored key there is nothing to carry over.
        mockMvc.perform(write(put("/api/search/embedding-providers/" + id).content(provider(name, other, null, 3L)))
                        .with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endpoint").value(other));
    }

    @Test
    void theRebuildIsStartedRefusedTwiceCancelledSwitchedAndRestored() throws Exception {
        var created = mockMvc.perform(write(post("/api/search/embedding-providers").content(provider("tei-" + UUID.randomUUID(), "k", null)))
                .with(authentication(owner))).andReturn().getResponse().getContentAsString();
        UUID provider = UUID.fromString(json.readTree(created).path("id").asString());

        when(probe.probe(anyString(), anyString(), eq("Qwen/Qwen3-Embedding-0.6B"), any())).thenReturn(new EmbeddingProbe.Outcome(
                false, null, null, 5, EmbeddingProbe.Failure.REJECTED, "The endpoint rejected the API key."));
        mockMvc.perform(write(post("/api/search/settings/future").content(generation(provider))).with(authentication(owner)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEARCH_SETTINGS_EMBEDDING_REJECTED"));
        when(probe.probe(anyString(), anyString(), eq("Qwen/Qwen3-Embedding-0.6B"), any())).thenReturn(new EmbeddingProbe.Outcome(
                false, null, null, 5, EmbeddingProbe.Failure.UNREACHABLE, "The endpoint could not be reached."));
        mockMvc.perform(write(post("/api/search/settings/future").content(generation(provider))).with(authentication(owner)))
                .andExpect(status().isServiceUnavailable());
        when(probe.probe(anyString(), anyString(), eq("Qwen/Qwen3-Embedding-0.6B"), any()))
                .thenReturn(new EmbeddingProbe.Outcome(true, "Qwen/Qwen3-Embedding-0.6B", 1024, 12, null, null));

        mockMvc.perform(write(post("/api/search/settings/future").content(generation(provider))).with(authentication(owner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FUTURE"))
                .andExpect(jsonPath("$.model").value("Qwen/Qwen3-Embedding-0.6B"))
                .andExpect(jsonPath("$.automatic").value(false));
        mockMvc.perform(write(post("/api/search/settings/future").content(generation(provider))).with(authentication(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEARCH_SETTINGS_FUTURE_EXISTS"));
        mockMvc.perform(get("/api/search/settings").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.future.dataBoundary").value("INTERNAL"))
                .andExpect(jsonPath("$.rebuild.switchable").isBoolean());
        mockMvc.perform(write(delete("/api/search/settings/future")).with(authentication(owner))).andExpect(status().isNoContent());
        mockMvc.perform(write(delete("/api/search/settings/future")).with(authentication(owner))).andExpect(status().isNotFound());

        mockMvc.perform(write(post("/api/search/settings/future").content(generation(provider))).with(authentication(owner)))
                .andExpect(status().isCreated());
        // This database holds no documents, so the rebuilt index is complete at once.
        var switched = mockMvc.perform(write(post("/api/search/settings/future/switch")).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present.model").value("Qwen/Qwen3-Embedding-0.6B"))
                .andExpect(jsonPath("$.future").isEmpty())
                .andExpect(jsonPath("$.past", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(write(post("/api/search/settings/future/switch")).with(authentication(owner))).andExpect(status().isNotFound());
        String past = json.readTree(switched).path("past").get(0).path("id").asString();
        mockMvc.perform(write(post("/api/search/settings/past/" + UUID.randomUUID() + "/restore")).with(authentication(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(write(post("/api/search/settings/past/" + past + "/restore")).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.present.id").value(past));
    }

    @Test
    void theConnectionCheckAndPresetsAnswerAModelManager() throws Exception {
        mockMvc.perform(write(post("/api/search/embedding-providers/test").content("""
                        {"providerId":null,"endpoint":"http://172.24.244.79:18090/v1","apiKey":"k","model":"Qwen/Qwen3-Embedding-0.6B","dimensions":null}
                        """)).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.dimensions").value(1024))
                .andExpect(jsonPath("$.error").isEmpty());
        mockMvc.perform(write(post("/api/search/embedding-providers/test").content(test(UUID.randomUUID()))).with(authentication(owner)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/search/embedding-models").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].model").value("Qwen/Qwen3-Embedding-0.6B"))
                .andExpect(jsonPath("$[0].dimensions").value(1024));
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON);
    }

    private static String generation(UUID provider) {
        return """
                {"providerId":"%s","model":"Qwen/Qwen3-Embedding-0.6B","dimensions":1024,
                 "queryPrefix":"Instruct: Given a question, retrieve passages that answer it\\nQuery: ","documentPrefix":"",
                 "minimumSemanticScore":0.7}
                """.formatted(provider);
    }

    private static String provider(String name, String key, Long revision) {
        return provider(name, "http://172.24.244.79:18090/v1", key, revision);
    }

    private static String provider(String name, String endpoint, String key, Long revision) {
        return """
                {"name":"%s","endpoint":"%s","apiKey":%s,"dataBoundary":"INTERNAL","revision":%s}
                """.formatted(name, endpoint, key == null ? "null" : "\"" + key + "\"", revision == null ? "null" : revision);
    }

    private static String test(UUID provider, String endpoint, String key) {
        return """
                {"providerId":"%s","endpoint":"%s","apiKey":%s,"model":"Qwen/Qwen3-Embedding-0.6B","dimensions":1024}
                """.formatted(provider, endpoint, key == null ? "null" : "\"" + key + "\"");
    }

    private static String test(UUID provider) {
        return """
                {"providerId":"%s","endpoint":null,"apiKey":null,"model":"Qwen/Qwen3-Embedding-0.6B","dimensions":1024}
                """.formatted(provider);
    }

    private static HttpServer startIdentityServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/.well-known/openid-configuration", exchange -> {
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
                byte[] body = """
                        {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token","jwks_uri":"%s/jwks",
                         "userinfo_endpoint":"%s/userinfo","subject_types_supported":["public"],
                         "id_token_signing_alg_values_supported":["RS256"]}
                        """.formatted(issuer, issuer, issuer, issuer, issuer).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) { response.write(body); }
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start local identity server", exception);
        }
    }
}
