package io.memoryos.api.source;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.api.ApiPostgresDatabase;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointSourceService;
import io.memoryos.connector.SharePointSourceService.Configuration;
import io.memoryos.connector.SharePointSourceService.RootPage;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SharePointSourceService.SelectionPolicy;
import io.memoryos.connector.SharePointSourceService.SelectionReceipt;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IdentityContext;
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

/**
 * The SharePoint Source endpoints: what they route, what they require, and what they answer. Authority
 * lives in the application service, so here its denial must surface as 403.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "memoryos.google-drive.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.google-drive.credential-key-version=test-v1",
        "memoryos.sharepoint.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.sharepoint.credential-key-version=sp-test-v1",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000025",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000025",
        "memoryos.initial-tenant.owner-subject=sharepoint-source-owner",
        "memoryos.initial-tenant.slug=sharepoint-source",
        "memoryos.initial-tenant.display-name=SharePoint source",
        "memoryos.initial-tenant.change-reference=MEM-126-SOURCE-TEST",
        "memoryos.search.endpoint=http://127.0.0.1:1",
})
@AutoConfigureMockMvc(addFilters = true)
@Testcontainers(disabledWithoutDocker = true)
class SharePointSourceApiTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER = "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();
    private static final UUID SOURCE = UUID.fromString("2b4c5d6e-7f80-4912-a345-6789abcdef01");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    // The selection processor needs the concrete service, so the mock replaces that bean rather than the port.
    @MockitoBean
    private io.memoryos.connector.sharepoint.DefaultSharePointSourceService sources;

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
                SELECT actor_id FROM external_identity_bindings WHERE subject = 'sharepoint-source-owner'
                """).query(UUID.class).single())));
    }

    @Test
    void creatingASourceAcceptsTheScopeForVerification() throws Exception {
        when(sources.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(receipt());

        mockMvc.perform(post("/api/sources/sharepoint")
                        .with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/sources/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()))
                .andExpect(jsonPath("$.operation.type").value("VALIDATE_SHAREPOINT_SELECTION"));
    }

    @Test
    void replacingTheScopeNeedsTheRevisionAndCsrf() throws Exception {
        when(sources.replaceScope(any(), any(), any(), anyLong(), anyLong(), any())).thenReturn(receipt());

        mockMvc.perform(put("/api/sources/{id}/sharepoint/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/sources/{id}/sharepoint/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scopeBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));
        verify(sources).replaceScope(any(), any(), any(), eq(3L), eq(2L), any());
    }

    @Test
    void configurationAndRootsAreReadable() throws Exception {
        when(sources.configuration(any(), any())).thenReturn(configuration());
        when(sources.roots(any(), any(), any(), anyInt())).thenReturn(new RootPage(4L, List.of(
                new SharePointSourceService.RootView("https://contoso.sharepoint.com/sites/Finance",
                        SharePointSourceService.RootKind.SITE, "Finance", true)), "1", 2L));

        mockMvc.perform(get("/api/sources/{id}/sharepoint", SOURCE).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"))
                .andExpect(jsonPath("$.pruneIntervalHours").value(168))
                .andExpect(jsonPath("$.tenantHost").value("contoso.sharepoint.com"));

        mockMvc.perform(get("/api/sources/{id}/sharepoint/roots", SOURCE).with(authentication(owner))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roots[0].kind").value("SITE"))
                .andExpect(jsonPath("$.nextCursor").value("1"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void scheduleAndPauseCarryTheScheduleRevision() throws Exception {
        when(sources.updateSchedule(any(), any(), anyLong(), anyInt(), anyInt())).thenReturn(configuration());
        when(sources.setPaused(any(), any(), anyLong(), anyBoolean())).thenReturn(configuration());

        mockMvc.perform(put("/api/sources/{id}/sharepoint/schedule", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncIntervalMinutes\":30,\"pruneIntervalHours\":0}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""));
        verify(sources).updateSchedule(any(), any(), eq(7L), eq(30), eq(0));

        mockMvc.perform(post("/api/sources/{id}/sharepoint/pause", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7,\"paused\":true}"))
                .andExpect(status().isOk());
        verify(sources).setPaused(any(), any(), eq(7L), eq(true));
    }

    @Test
    void synchronizeSchedulesARun() throws Exception {
        when(sources.synchronize(any(), any())).thenReturn(new SourceOperationView(
                new SourceOperationId(UUID.randomUUID()), SourceOperationType.SYNC_SOURCE,
                SourceOperationStatus.NOT_STARTED, Instant.now(), null, null));

        mockMvc.perform(post("/api/sources/{id}/sharepoint/sync", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("SYNC_SOURCE"));
    }

    @Test
    void deniedAuthorityAndInvalidScopeSurfaceAsProblems() throws Exception {
        when(sources.selectionPolicy(any())).thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "no management"));
        mockMvc.perform(get("/api/sources/sharepoint/selection-policy").with(authentication(owner)))
                .andExpect(status().isForbidden());

        when(sources.create(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(SourceException.invalid("Select document libraries, site pages, or both.",
                        "SharePoint source selects neither documents nor pages"));
        mockMvc.perform(post("/api/sources/sharepoint")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_INVALID_REQUEST"));

        mockMvc.perform(get("/api/sources/sharepoint/selection-policy")).andExpect(status().isUnauthorized());
    }

    @Test
    void selectionPolicyAndReceiptAreReadable() throws Exception {
        reset(sources);
        when(sources.selectionPolicy(any())).thenReturn(new SelectionPolicy(1000, 100, 3_145_728));
        when(sources.selectionRequest(any(), any())).thenReturn(receipt());

        mockMvc.perform(get("/api/sources/sharepoint/selection-policy").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRootsPerSource").value(1000));

        mockMvc.perform(get("/api/sources/sharepoint/selection-requests/{id}", UUID.randomUUID())
                        .with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));
    }

    private static SelectionReceipt receipt() {
        return new SelectionReceipt(new SourceId(SOURCE), new SourceOperationView(
                new SourceOperationId(UUID.randomUUID()), SourceOperationType.VALIDATE_SHAREPOINT_SELECTION,
                SourceOperationStatus.NOT_STARTED, Instant.now(), null, null));
    }

    private static Configuration configuration() {
        return new Configuration(new SourceId(SOURCE), new CredentialId(UUID.randomUUID()), "Entra app", "ACTIVE",
                2L, 4L, ScopeMode.SPECIFIC, 2L, List.of(), List.of("*/Archive/*"), true, false, 30, 168, 7L,
                false, "contoso.sharepoint.com", null, null, false, null, null);
    }

    private static String createBody() {
        return """
                {"requestId":"%s","name":"Finance","credentialId":"%s","access":"PUBLIC",
                 "scope":{"scopeMode":"SPECIFIC","siteUrls":["https://contoso.sharepoint.com/sites/Finance"],
                 "includeDocuments":true,"includePages":false,"syncIntervalMinutes":30,"pruneIntervalHours":168}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
    }

    private static String scopeBody() {
        return """
                {"requestId":"%s","expectedCredentialRevision":2,
                 "scope":{"scopeMode":"ALL_SITES","excludedSites":["*/personal/*"],
                 "includeDocuments":true,"includePages":false,"syncIntervalMinutes":60,"pruneIntervalHours":24}}
                """.formatted(UUID.randomUUID());
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
