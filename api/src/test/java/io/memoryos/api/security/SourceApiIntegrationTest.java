package io.memoryos.api.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.document.DocumentId;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.document.DocumentCommandService;
import io.memoryos.identity.ActorId;
import io.memoryos.identity.IdentityContext;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.ingestion.application.DefaultIndexingCoordinator;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "memoryos.initial-organization.owner-subject=source-owner",
        "memoryos.initial-organization.slug=sources",
        "memoryos.initial-organization.display-name=Sources",
        "memoryos.initial-organization.change-reference=MEM-35-TEST",
        "spring.datasource.url=jdbc:h2:mem:source-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SourceApiIntegrationTest {

    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SourceDocumentAccessResolver documentAccess;

    @Autowired
    private ConnectorIndexingPort indexingPort;

    @Autowired
    private ConnectorCleanupPort cleanupPort;

    @Autowired
    private DocumentCommandService documents;

    @Autowired
    private SourceContentExtractor extractor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ActorSessionAuthenticationToken owner;
    private ActorSessionAuthenticationToken member;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
        registry.add("memoryos.identity.keycloak.admin.client-secret", () -> "test-provisioner-secret");
        registry.add(
                "memoryos.identity.keycloak.admin.action-redirect-uri",
                () -> "http://127.0.0.1/invite/activate"
        );
    }

    @AfterAll
    static void stopIdentityServer() {
        IDENTITY_SERVER.stop(0);
    }

    @BeforeEach
    void seedActors() {
        UUID organizationId = jdbcClient.sql("SELECT id FROM organizations WHERE slug = 'sources'")
                .query(UUID.class)
                .single();
        UUID ownerActorId = ownerActorId();
        UUID memberActorId = UUID.fromString("5b38e8dd-6c42-41ff-b392-7942808ce3af");
        int actorCount = jdbcClient.sql("SELECT COUNT(*) FROM actors WHERE id = :id")
                .param("id", memberActorId)
                .query(Integer.class)
                .single();
        if (actorCount == 0) {
            jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)")
                    .param("id", memberActorId)
                    .update();
        }
        int membershipCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM organization_memberships
                        WHERE organization_id = :organizationId AND actor_id = :actorId
                        """)
                .param("organizationId", organizationId)
                .param("actorId", memberActorId)
                .query(Integer.class)
                .single();
        if (membershipCount == 0) {
            jdbcClient.sql("""
                            INSERT INTO organization_memberships (
                                organization_id, actor_id, role, status
                            ) VALUES (:organizationId, :actorId, 'MEMBER', 'ACTIVE')
                            """)
                    .param("organizationId", organizationId)
                    .param("actorId", memberActorId)
                    .update();
        }
        owner = token(ownerActorId);
        member = token(memberActorId);
    }

    @Test
    void indexesAndCleansUpOneFileThroughTheAuthorizedApi() throws Exception {
        String sourceBody = mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Product documentation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        String sourceId = io.swagger.v3.core.util.Json.mapper().readTree(sourceBody)
                .path("source").path("id").textValue();

        var file = new MockMultipartFile(
                "file",
                "knowledge.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "MemoryOS FILE connector content".getBytes(UTF_8)
        );
        String uploadBody = mockMvc.perform(multipart("/api/sources/{sourceId}/items", sourceId)
                        .file(file)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.item.status").value("PENDING"))
                .andExpect(jsonPath("$.operation.status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        String itemId = io.swagger.v3.core.util.Json.mapper().readTree(uploadBody)
                .path("item").path("id").textValue();

        coordinator().processAvailable(8);
        UUID documentId = jdbcClient.sql("""
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE connector_credential_pair_id = :sourceId
                        """)
                .param("sourceId", UUID.fromString(sourceId))
                .query(UUID.class)
                .single();
        assertTrue(documentAccess.canRead(
                owner.getPrincipal().actorId(),
                new DocumentId(documentId)
        ));
        assertTrue(documentAccess.canRead(
                member.getPrincipal().actorId(),
                new DocumentId(documentId)
        ));
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.status").value("ACTIVE"))
                .andExpect(jsonPath("$.source.documentCount").value(1))
                .andExpect(jsonPath("$.items[0].status").value("INDEXED"));

        mockMvc.perform(post("/api/sources/{sourceId}/items/{itemId}/remove", sourceId, itemId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("REMOVE_ITEM"));
        assertFalse(documentAccess.canRead(
                member.getPrincipal().actorId(),
                new DocumentId(documentId)
        ));
        coordinator().processAvailable(8);
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        String deleteBody = mockMvc.perform(post("/api/sources/{sourceId}/delete", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("DELETE_SOURCE"))
                .andReturn().getResponse().getContentAsString();
        String deleteOperationId = io.swagger.v3.core.util.Json.mapper().readTree(deleteBody)
                .path("id").textValue();
        coordinator().processAvailable(8);
        mockMvc.perform(post("/api/sources/{sourceId}/delete", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(deleteOperationId));
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }

    @Test
    void rejectsMemberManagementAndBothValidationFailureShapes() throws Exception {
        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(member))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forbidden\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));

        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        String sourceBody = mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Validation source\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sourceId = io.swagger.v3.core.util.Json.mapper().readTree(sourceBody)
                .path("source").path("id").textValue();
        mockMvc.perform(get("/api/sources/{sourceId}/index-attempts?size=0", sourceId)
                        .with(authentication(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));

        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").doesNotExist());
        var oversized = new MockMultipartFile(
                "file",
                "oversized.txt",
                MediaType.TEXT_PLAIN_VALUE,
                new byte[10 * 1024 * 1024 + 1]
        );
        mockMvc.perform(multipart("/api/sources/{sourceId}/items", sourceId)
                        .file(oversized)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_INVALID_REQUEST"));

    }

    private DefaultIndexingCoordinator coordinator() {
        return new DefaultIndexingCoordinator(
                indexingPort,
                cleanupPort,
                documents,
                extractor,
                new TransactionTemplate(transactionManager)
        );
    }

    private UUID ownerActorId() {
        return jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'source-owner'
                        """)
                .param("issuer", "https://issuer.example.test")
                .query(UUID.class)
                .single();
    }

    private static ActorSessionAuthenticationToken token(UUID actorId) {
        return new ActorSessionAuthenticationToken(new IdentityContext(new ActorId(actorId)));
    }

    private static HttpServer startIdentityServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/.well-known/openid-configuration", exchange -> {
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
                byte[] body = """
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/authorize",
                          "token_endpoint": "%s/token",
                          "jwks_uri": "%s/jwks",
                          "userinfo_endpoint": "%s/userinfo",
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """.formatted(issuer, issuer, issuer, issuer, issuer).getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start local identity server", exception);
        }
    }
}
