package io.memoryos.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "memoryos.initial-organization.owner-subject=openapi-owner",
        "memoryos.initial-organization.slug=openapi",
        "memoryos.initial-organization.display-name=OpenAPI",
        "memoryos.initial-organization.default-workspace-slug=default",
        "memoryos.initial-organization.default-workspace-display-name=Default",
        "memoryos.initial-organization.change-reference=TEST-OPENAPI-CONTRACT",
        "spring.datasource.url=jdbc:h2:mem:openapi-contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractTest {

    private static final String WRITE_FLAG = "MEMORYOS_OPENAPI_WRITE";
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();
    private static final Set<String> BROWSER_API_PATHS = Set.of(
            "/api/identity/me",
            "/api/invitations",
            "/api/invitations/current",
            "/api/invitations/{invitationId}",
            "/api/invitations/{invitationId}/rotate"
    );

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
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

        Path contract = repositoryRoot().resolve("openapi.yml");
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
