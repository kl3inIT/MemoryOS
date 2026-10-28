package io.memoryos.api.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.api.ApiPostgresDatabase;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkCredentialService;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IdentityContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "memoryos.google-drive.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.google-drive.credential-key-version=test-v1",
        "memoryos.lark.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.lark.credential-key-version=lark-test-v1",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000245",
        "memoryos.initial-tenant.owner-subject=lark-owner",
        "memoryos.initial-tenant.slug=lark",
        "memoryos.initial-tenant.display-name=Lark",
        "memoryos.initial-tenant.change-reference=MEM-245-TEST",
        "memoryos.search.endpoint=http://127.0.0.1:1",
})
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class LarkCredentialApiTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private LarkCredentialService credentials;

    private ActorAuthenticationToken owner;

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

    @BeforeEach
    void seedOwner() {
        owner = token(ownerActorId());
    }

    private UUID ownerActorId() {
        return jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'lark-owner'
                        """)
                .param("issuer", "https://issuer.example.test")
                .query(UUID.class)
                .single();
    }

    private static ActorAuthenticationToken token(UUID actorId) {
        return new ActorAuthenticationToken(new IdentityContext(new ActorId(actorId)));
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

    private static LarkCredentialService.CredentialView view(CredentialId id, String name) {
        return new LarkCredentialService.CredentialView(id, name, "LARK", "cli_a1b2c3d4e5f6a7b8", "ACTIVE",
                "MemoryOS Test", List.of("im:chat:readonly"), 1L, Instant.now(), Instant.now(), 0L,
                List.of("rename", "replace_secret", "test", "delete"));
    }

    private static String body(String name) {
        return """
                {"name":"%s","region":"LARK","appId":"cli_a1b2c3d4e5f6a7b8","appSecret":"app-secret-value"}
                """.formatted(name);
    }

    @Test
    void listRequiresAuthenticationAndReturnsNoSecret() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.list(any())).thenReturn(List.of(view(id, "Lark app")));
        mockMvc.perform(get("/api/credentials/lark").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.value().toString()))
                .andExpect(jsonPath("$[0].name").value("Lark app"))
                .andExpect(jsonPath("$[0].appId").value("cli_a1b2c3d4e5f6a7b8"))
                .andExpect(jsonPath("$[0].botName").value("MemoryOS Test"))
                .andExpect(jsonPath("$[0].missingScopes[0]").value("im:chat:readonly"))
                .andExpect(jsonPath("$[0].appSecret").doesNotExist());
        mockMvc.perform(get("/api/credentials/lark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createPassesTheSecretOnAndReturnsCreated() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        var secret = new byte[1][];
        when(credentials.create(any(), any())).thenAnswer(call -> {
            LarkCredentialService.Draft draft = call.getArgument(1);
            secret[0] = draft.appSecret();
            assertEquals("cli_a1b2c3d4e5f6a7b8", draft.appId());
            return id;
        });
        when(credentials.list(any())).thenReturn(List.of(view(id, "Lark app")));
        mockMvc.perform(post("/api/credentials/lark")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Lark app")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.appSecret").doesNotExist());
        assertArrayEquals("app-secret-value".getBytes(StandardCharsets.UTF_8), secret[0]);
    }

    @Test
    void createRejectsAMalformedBodyBeforeTheService() throws Exception {
        for (String malformed : List.of(
                "{\"name\":\"Bad\",\"region\":\"LARK\",\"appId\":\"cli_a1b2c3d4e5f6a7b8\"}",
                "{\"name\":\"Bad\",\"region\":\"MARS\",\"appId\":\"cli_a1b2c3d4e5f6a7b8\",\"appSecret\":\"s\"}",
                "{\"name\":\"\",\"region\":\"LARK\",\"appId\":\"cli_a1b2c3d4e5f6a7b8\",\"appSecret\":\"s\"}")) {
            mockMvc.perform(post("/api/credentials/lark")
                            .with(authentication(owner))
                            .header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(credentials);
    }

    @Test
    void replaceSecretRequiresIfMatch() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.list(any())).thenReturn(List.of(view(id, "Lark app")));
        mockMvc.perform(put("/api/credentials/lark/{id}/secret", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Lark app")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/credentials/lark/{id}/secret", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Lark app")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()));
        verify(credentials).replaceSecret(any(), eq(id), eq(1L), any());
    }

    @Test
    void renameAndDeleteCarryTheRevision() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        mockMvc.perform(put("/api/credentials/lark/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isNoContent());
        verify(credentials).rename(any(), eq(id), eq(1L), eq("Renamed"));
        mockMvc.perform(delete("/api/credentials/lark/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isNoContent());
        verify(credentials).delete(any(), eq(id), eq(1L));
    }

    @Test
    void theTestEndpointReturnsTheBotAndTheMissingScopesSorted() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.test(any(), eq(id))).thenReturn(new LarkCredentialService.TestResult("MemoryOS Test",
                Set.of("wiki:wiki:readonly", "drive:drive:readonly")));
        mockMvc.perform(post("/api/credentials/lark/{id}/test", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botName").value("MemoryOS Test"))
                .andExpect(jsonPath("$.missingScopes[0]").value("drive:drive:readonly"))
                .andExpect(jsonPath("$.missingScopes[1]").value("wiki:wiki:readonly"));
    }
}
