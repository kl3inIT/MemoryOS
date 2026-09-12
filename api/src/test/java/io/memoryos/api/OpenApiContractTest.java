package io.memoryos.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.owner-subject=openapi-owner",
        "memoryos.initial-tenant.slug=openapi",
        "memoryos.initial-tenant.display-name=OpenAPI",
        "memoryos.initial-tenant.change-reference=TEST-OPENAPI-CONTRACT",
})
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest {

    private static final String WRITE_FLAG = "MEMORYOS_OPENAPI_WRITE";
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();
    private static final Set<String> BROWSER_API_PATHS = Set.of(
            "/api/chat/files",
            "/api/chat/files/policy",
            "/api/chat/files/uploads",
            "/api/chat/files/{fileId}",
            "/api/chat/files/{fileId}/text",
            "/api/chat/files/{fileId}/passages",
            "/api/chat/files/{fileId}/content",
            "/api/chat/files/{fileId}/finalize",
            "/api/chat/files/{fileId}/retry",
            "/api/chat/model-default",
            "/api/chat/models",
            "/api/chat/models/{modelId}",
            "/api/chat/models/{modelId}/validate",
            "/api/chat/personas/{personaId}/model",
            "/api/chat/personas",
            "/api/chat/personas/{personaId}",
            "/api/chat/personas/{personaId}/models",
            "/api/chat/personas/sources",
            "/api/chat/projects",
            "/api/chat/projects/{projectId}",
            "/api/chat/projects/{projectId}/sessions",
            "/api/chat/provider-adapters",
            "/api/chat/providers",
            "/api/chat/providers/{providerId}",
            "/api/chat/providers/{providerId}/models",
            "/api/chat/sessions",
            "/api/chat/sessions/{sessionId}",
            "/api/chat/sessions/{sessionId}/title",
            "/api/chat/sessions/{sessionId}/branches",
            "/api/chat/sessions/{sessionId}/branch",
            "/api/chat/sessions/{sessionId}/persona",
            "/api/chat/sessions/{sessionId}/project",
            "/api/chat/sessions/{sessionId}/sharing",
            "/api/chat/sessions/{sessionId}/settings",
            "/api/chat/sessions/{sessionId}/feedback",
            "/api/chat/shared/{sessionId}",
            "/api/chat/shared/{sessionId}/messages",
            "/api/chat/sessions/{sessionId}/messages",
            "/api/chat/sessions/{sessionId}/messages/{assistantMessageId}/cancel",
            "/api/chat/sessions/{sessionId}/messages/{assistantMessageId}/events",
            "/api/chat/sessions/{sessionId}/messages/{assistantMessageId}/feedback",
            "/api/chat/sessions/{sessionId}/messages/{userMessageId}/edit",
            "/api/chat/sessions/{sessionId}/messages/{userMessageId}/regenerate",
            "/api/search",
            "/api/search/documents/{documentId}",
            "/api/identity/me",
            "/api/users",
            "/api/users/{actorId}/activate",
            "/api/users/{actorId}/deactivate",
            "/api/users/{actorId}/groups",
            "/api/groups",
            "/api/groups/capabilities",
            "/api/groups/{groupId}",
            "/api/groups/{groupId}/rename",
            "/api/groups/{groupId}/delete",
            "/api/groups/{groupId}/members",
            "/api/groups/{groupId}/candidates",
            "/api/groups/{groupId}/members/{actorId}/remove",
            "/api/groups/{groupId}/members/{actorId}/assign-manager",
            "/api/groups/{groupId}/members/{actorId}/remove-manager",
            "/api/groups/{groupId}/capabilities",
            "/api/groups/{groupId}/sources",
            "/api/invitations",
            "/api/invitations/current",
            "/api/invitations/{invitationId}/revoke",
            "/api/invitations/{invitationId}/rotate",
            "/api/source-operations/{operationId}",
            "/api/sources",
            "/api/sources/file",
            "/api/sources/google-drive",
            "/api/sources/google-drive/selection-policy",
            "/api/sources/google-drive/selection-requests/{requestId}",
            "/api/credentials/google-drive",
            "/api/credentials/google-drive/authorization",
            "/api/credentials/google-drive/{credentialId}",
            "/api/credentials/google-drive/{credentialId}/revoke",
            "/api/sources/{sourceId}/google-drive",
            "/api/sources/{sourceId}/google-drive/selection",
            "/api/sources/{sourceId}/google-drive/selection-tree",
            "/api/sources/{sourceId}/google-drive/selection-draft",
            "/api/sources/{sourceId}/google-drive/roots",
            "/api/sources/{sourceId}/google-drive/linked-documents/discover",
            "/api/sources/{sourceId}/google-drive/schedule",
            "/api/sources/{sourceId}/google-drive/sync",
            "/api/sources/group-options",
            "/api/sources/{sourceId}/groups",
            "/api/sources/{sourceId}",
            "/api/sources/{sourceId}/delete",
            "/api/sources/{sourceId}/index-attempts",
            "/api/sources/{sourceId}/runs",
            "/api/sources/{sourceId}/runs/{runId}",
            "/api/sources/{sourceId}/runs/{runId}/errors",
            "/api/sources/{sourceId}/items",
            "/api/sources/{sourceId}/uploads",
            "/api/sources/{sourceId}/uploads/{uploadId}/finalize",
            "/api/sources/{sourceId}/items/{itemId}/index-attempts",
            "/api/sources/{sourceId}/items/{itemId}/remove"
    );

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        ApiPostgresDatabase.configure(registry);
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

    @Test
    void committedContractDescribesOnlyTheLiveBrowserApi() throws Exception {
        String generated = mockMvc.perform(get("/v3/api-docs/browser"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode actual = Json.mapper().readTree(generated);

        TreeSet<String> actualPaths = new TreeSet<>();
        actual.path("paths").fieldNames().forEachRemaining(actualPaths::add);
        assertEquals(BROWSER_API_PATHS, actualPaths);
        for (var path : BROWSER_API_PATHS) {
            for (var method : Set.of("post", "put", "patch", "delete")) {
                var operation = actual.path("paths").path(path).path(method);
                if (operation.isMissingNode()) continue;
                boolean csrf = false;
                for (var parameter : operation.path("parameters")) {
                    if (parameter.path("name").asText().equals("X-MemoryOS-CSRF")) {
                        csrf = parameter.path("required").asBoolean()
                                && parameter.path("in").asText().equals("header");
                    }
                }
                assertTrue(csrf, method + " " + path + " must document the mutation header");
            }
        }
        assertEquals("#/components/schemas/ChatModelValidationResult", actual.path("paths")
                .path("/api/chat/models/{modelId}/validate").path("post").path("responses").path("200")
                .path("content").path("application/json").path("schema").path("$ref").asText());
        var validationFields = actual.path("components").path("schemas").path("ChatModelValidationResult").path("properties");
        assertTrue(validationFields.has("reachable"));
        assertTrue(validationFields.has("failureCode"));
        assertEquals(2, validationFields.size());
        var searchSource = actual.path("components").path("schemas").path("SearchEvent")
                .path("properties").path("source");
        assertFalse(searchSource.has("$ref"), "A sibling object reference would reject null progress sources");
        assertEquals(2, searchSource.path("oneOf").size());
        assertEquals("#/components/schemas/ChatSource", searchSource.path("oneOf").get(0).path("$ref").asText());
        assertEquals("null", searchSource.path("oneOf").get(1).path("type").asText());
        for (var path : Set.of("/api/chat/models", "/api/chat/providers", "/api/chat/provider-adapters", "/api/chat/model-default")) {
            assertTrue(actual.path("paths").path(path).path("get").path("responses").path("200")
                    .path("content").path("application/json").path("schema").isObject(), path + " must generate a typed success response");
        }
        for (var path : BROWSER_API_PATHS.stream().filter(value -> value.startsWith("/api/chat/")).toList()) {
            for (var operation : actual.path("paths").path(path)) {
                for (var code : Set.of("400", "403", "404")) {
                    assertEquals("#/components/schemas/ApiProblem", operation.path("responses").path(code)
                            .path("content").path("application/problem+json").path("schema").path("$ref").textValue(), path + " " + code);
                }
            }
        }
        JsonNode revokeOperation = actual.path("paths")
                .path("/api/invitations/{invitationId}/revoke")
                .path("post");
        assertEquals("revokeInvitation", revokeOperation.path("operationId").textValue());
        assertEquals("Invitations", revokeOperation.path("tags").path(0).textValue());
        assertEquals(
                "Identity",
                actual.path("paths")
                        .path("/api/identity/me")
                        .path("get")
                        .path("tags")
                        .path(0)
                        .textValue()
        );
        assertFalse(actual.path("paths").has("/api/invitations/{invitationId}"));
        assertEquals(
                "uri-reference",
                actual.path("components")
                        .path("schemas")
                        .path("ApiProblem")
                        .path("properties")
                        .path("instance")
                        .path("format")
                        .textValue()
        );
        JsonNode tenantSchema = actual.path("components")
                .path("schemas")
                .path("CurrentIdentity")
                .path("properties")
                .path("tenant");
        assertEquals(2, tenantSchema.path("oneOf").size());
        assertEquals(
                "#/components/schemas/CurrentTenant",
                tenantSchema.path("oneOf").path(0).path("$ref").textValue()
        );
        assertEquals("null", tenantSchema.path("oneOf").path(1).path("type").textValue());

        for (String name : Set.of("SearchPage", "Result", "Section", "ChunkProvenance", "Passage", "SearchDocument", "ChatSession", "ChatMessage")) {
            JsonNode schema = actual.path("components").path("schemas").path(name);
            Set<String> fields = new TreeSet<>();
            schema.path("properties").fieldNames().forEachRemaining(fields::add);
            Set<String> required = new TreeSet<>();
            schema.path("required").forEach(value -> required.add(value.asText()));
            assertFalse(fields.isEmpty(), name);
            assertEquals(fields, required, name + " response fields must be required");
        }

        Path contract = repositoryRoot().resolve("openapi.yml");
        for (String property : Set.of("personaId", "projectId")) {
            JsonNode schema = actual.path("components").path("schemas").path("CreateChatSession").path("properties").path(property);
            assertEquals("uuid", schema.path("oneOf").path(0).path("format").textValue());
            assertEquals("null", schema.path("oneOf").path(1).path("type").textValue());
        }
        if (Boolean.parseBoolean(System.getenv(WRITE_FLAG))) {
            Files.writeString(contract, Yaml.pretty(actual));
            return;
        }

        JsonNode expected = Yaml.mapper().readTree(Files.readString(contract));
        assertEquals(
                expected,
                actual,
                "openapi.yml is stale; refresh it with "
                        + "$env:MEMORYOS_OPENAPI_WRITE='true'; "
                        + ".\\gradlew.bat :api:test --tests '*OpenApiContractTest*'"
        );
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Could not locate the repository root from the test working directory");
        }
        return candidate;
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
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start test identity server", exception);
        }
    }
}
