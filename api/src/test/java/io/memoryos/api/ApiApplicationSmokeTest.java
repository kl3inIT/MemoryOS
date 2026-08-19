package io.memoryos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
                "memoryos.identity.audience=memoryos-api",
                "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
                "memoryos.initial-organization.owner-subject=smoke-owner",
                "memoryos.initial-organization.slug=smoke",
                "memoryos.initial-organization.display-name=Smoke",
                "memoryos.initial-organization.default-workspace-slug=default",
                "memoryos.initial-organization.default-workspace-display-name=Default",
                "memoryos.initial-organization.change-reference=TEST-SMOKE-BOOTSTRAP",
                "spring.datasource.url=jdbc:h2:mem:api-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class ApiApplicationSmokeTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
    }

    @AfterAll
    static void stopIdentityServer() {
        IDENTITY_SERVER.stop(0);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpointIsAvailable(@LocalServerPort int port) throws Exception {
        try (var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UP\""));
        }
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
