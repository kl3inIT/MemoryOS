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
import io.memoryos.connector.SharePointCredentialService;
import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SourceException;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.IdentityContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
        "memoryos.sharepoint.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.sharepoint.credential-key-version=sp-test-v1",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.owner-subject=sharepoint-owner",
        "memoryos.initial-tenant.slug=sharepoint",
        "memoryos.initial-tenant.display-name=SharePoint",
        "memoryos.initial-tenant.change-reference=MEM-126-TEST",
        "memoryos.search.endpoint=http://127.0.0.1:1",
})
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class SharePointCredentialApiTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private SharePointCredentialService credentials;

    private ActorAuthenticationToken owner;
    private ActorAuthenticationToken member;

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
    void seedActors() {
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
                        SELECT COUNT(*) FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", tenantId())
                .param("actorId", memberActorId)
                .query(Integer.class)
                .single();
        if (membershipCount == 0) {
            jdbcClient.sql("""
                            INSERT INTO tenant_memberships (
                                tenant_id, actor_id, role, status
                            ) VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
                            """)
                    .param("tenantId", tenantId())
                    .param("actorId", memberActorId)
                    .update();
        }
        jdbcClient.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id, is_manager)
                        SELECT tenant_id, id, :actorId, FALSE
                        FROM iam_groups
                        WHERE tenant_id = :tenantId AND system_key = 'BASIC'
                        ON CONFLICT DO NOTHING
                        """)
                .param("tenantId", tenantId())
                .param("actorId", memberActorId)
                .update();
        owner = token(ownerActorId);
        member = token(memberActorId);
    }

    private UUID tenantId() {
        return jdbcClient.sql("SELECT id FROM tenants WHERE slug = 'sharepoint'")
                .query(UUID.class)
                .single();
    }

    private UUID ownerActorId() {
        return jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'sharepoint-owner'
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

    private static IamException deniedManagement() {
        return new IamException(IamFailureReason.ACCESS_DENIED, "actor lacks SOURCES_MANAGE");
    }

    private static SharePointCredentialService.CredentialView view(CredentialId id, String name) {
        return new SharePointCredentialService.CredentialView(id, name, UUID.randomUUID(), UUID.randomUUID(),
                "GLOBAL", "CLIENT_SECRET", "ACTIVE", null, null, "tenant.sharepoint.com", 1L,
                Instant.now(), Instant.now(), 0L, List.of("rename", "replace_authentication", "test", "delete"));
    }

    private static String secretBody(String name) {
        return """
                {"name":"%s","directoryId":"%s","clientId":"%s","cloud":"GLOBAL","authMethod":"CLIENT_SECRET","clientSecret":"secret"}
                """.formatted(name, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void listRequiresManagementAndReturnsNoSecrets() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.list(any())).thenReturn(List.of(view(id, "Entra app")));
        mockMvc.perform(get("/api/credentials/sharepoint").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(id.value().toString()))
                .andExpect(jsonPath("$[0].name").value("Entra app"))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$[0].certificate").doesNotExist());
        when(credentials.list(any())).thenThrow(deniedManagement());
        mockMvc.perform(get("/api/credentials/sharepoint").with(authentication(member)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/credentials/sharepoint"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRequiresCsrfAndReturnsCreated() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.create(any(), any())).thenReturn(id);
        when(credentials.list(any())).thenReturn(List.of(view(id, "Entra app")));
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Entra app")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Entra app")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(id.value().toString()));
    }

    @Test
    void createRejectsInvalidSecretAndDirectory() throws Exception {
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"directoryId\":\"not-a-guid\",\"clientId\":\"%s\",\"cloud\":\"GLOBAL\",\"authMethod\":\"CLIENT_SECRET\",\"clientSecret\":\"secret\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_SHAREPOINT_DIRECTORY_INVALID"));
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"directoryId\":\"%s\",\"clientId\":\"%s\",\"cloud\":\"GLOBAL\",\"authMethod\":\"CLIENT_SECRET\"}"
                                .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_SHAREPOINT_SECRET_INVALID"));
    }

    @Test
    void rejectedCredentialReturnsValidationProblem() throws Exception {
        when(credentials.create(any(), any())).thenThrow(SharePointException.rejected(
                io.memoryos.connector.SharePointProviderException.Reason.INVALID_CLIENT_SECRET));
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Rejected")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_SHAREPOINT_CREDENTIAL_REJECTED"));
    }

    @Test
    void replaceAuthenticationRequiresIfMatchAndCsrf() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.list(any())).thenReturn(List.of(view(id, "Entra app")));
        mockMvc.perform(put("/api/credentials/sharepoint/{id}/authentication", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Entra app")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/credentials/sharepoint/{id}/authentication", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Entra app")))
                .andExpect(status().isOk());
        verify(credentials).replaceAuthentication(any(), eq(id), eq(1L), any());
    }

    @Test
    void renameAndDeleteRequireRevisionAndCsrf() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        mockMvc.perform(put("/api/credentials/sharepoint/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isNoContent());
        verify(credentials).rename(any(), eq(id), eq(1L), eq("Renamed"));
        mockMvc.perform(delete("/api/credentials/sharepoint/{id}", id.value())
                        .with(authentication(owner))
                        .header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/credentials/sharepoint/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isNoContent());
        verify(credentials).delete(any(), eq(id), eq(1L));
    }

    @Test
    void staleRevisionReturnsConflict() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        doThrow(SourceException.conflict("stale")).when(credentials).rename(any(), any(), anyLong(), anyString());
        mockMvc.perform(put("/api/credentials/sharepoint/{id}", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void testEndpointReturnsResult() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.test(any(), eq(id))).thenReturn(new SharePointCredentialService.TestResult(true, "tenant.sharepoint.com"));
        mockMvc.perform(post("/api/credentials/sharepoint/{id}/test", id.value())
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allSitesReadable").value(true))
                .andExpect(jsonPath("$.tenantHost").value("tenant.sharepoint.com"));
    }

    @Test
    void deniedManagementAuthorityBecomesForbidden() throws Exception {
        var id = new CredentialId(UUID.randomUUID());
        when(credentials.create(any(), any())).thenThrow(deniedManagement());
        doThrow(deniedManagement()).when(credentials).delete(any(), any(), anyLong());
        when(credentials.test(any(), any())).thenThrow(deniedManagement());
        mockMvc.perform(post("/api/credentials/sharepoint")
                        .with(authentication(member))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secretBody("Denied")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/credentials/sharepoint/{id}", id.value())
                        .with(authentication(member))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/credentials/sharepoint/{id}/test", id.value())
                        .with(authentication(member))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isForbidden());
    }
}
