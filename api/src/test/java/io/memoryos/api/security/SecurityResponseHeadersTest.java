package io.memoryos.api.security;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.api.ApiPostgresDatabase;
import io.memoryos.iam.IdentityContext;
import io.memoryos.shared.ActorId;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Controllers do not write {@code Cache-Control} or {@code X-Content-Type-Options}: Spring Security's default
 * header writer sends both on every response, JSON and download alike, and a controller writes its own
 * {@code Cache-Control} only to deliberately override it.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000026",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000026",
        "memoryos.initial-tenant.owner-subject=response-headers-owner",
        "memoryos.initial-tenant.slug=response-headers",
        "memoryos.initial-tenant.display-name=Response headers",
        "memoryos.initial-tenant.change-reference=PHASE4-RESPONSE-HEADERS",
        "memoryos.search.endpoint=http://127.0.0.1:1",
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecurityResponseHeadersTest {
    private static final String NOT_CACHED = "no-cache, no-store, max-age=0, must-revalidate";
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER = "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    private ActorAuthenticationToken owner;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        ApiPostgresDatabase.configure(registry);
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
        registry.add("memoryos.identity.keycloak.admin.client-secret", () -> "test-provisioner-secret");
        registry.add("memoryos.identity.keycloak.admin.action-redirect-uri", () -> "http://127.0.0.1/invite/activate");
    }

    @AfterAll
    static void stopIdentityServer() {
        IDENTITY_SERVER.stop(0);
    }

    @BeforeEach
    void seedActor() {
        owner = new ActorAuthenticationToken(new IdentityContext(new ActorId(jdbcClient.sql("""
                SELECT actor_id FROM external_identity_bindings WHERE subject = 'response-headers-owner'
                """).query(UUID.class).single())));
    }

    @Test
    void jsonReadIsNeitherCachedNorSniffed() throws Exception {
        expectDefaults(mockMvc.perform(get("/api/chat/library/usage").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json")));
    }

    @Test
    void downloadIsNeitherCachedNorSniffed() throws Exception {
        expectDefaults(mockMvc.perform(get("/api/audit/export").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;"))));
    }

    @Test
    void problemResponseIsNeitherCachedNorSniffed() throws Exception {
        expectDefaults(mockMvc.perform(get("/api/audit/events/" + UUID.randomUUID()).with(authentication(owner)))
                .andExpect(status().isNotFound()));
    }

    private static void expectDefaults(ResultActions response) throws Exception {
        response.andExpect(header().string("Cache-Control", NOT_CACHED))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
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
                        """.formatted(issuer, issuer, issuer, issuer, issuer).getBytes(StandardCharsets.UTF_8);
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
