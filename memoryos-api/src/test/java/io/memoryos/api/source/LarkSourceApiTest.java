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
import io.memoryos.connector.LarkSourceService.Configuration;
import io.memoryos.connector.LarkSourceService.Root;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.LarkSourceService.Scope;
import io.memoryos.connector.LarkSourceService.SelectionReceipt;
import io.memoryos.connector.LarkSourceService.SpaceOption;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.lark.DefaultLarkSourceService;
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

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "memoryos.google-drive.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.google-drive.credential-key-version=test-v1",
        "memoryos.lark.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.lark.credential-key-version=lark-test-v1",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000246",
        "memoryos.initial-tenant.owner-subject=lark-source-owner",
        "memoryos.initial-tenant.slug=lark-source",
        "memoryos.initial-tenant.display-name=Lark",
        "memoryos.initial-tenant.change-reference=MEM-245-TEST",
        "memoryos.search.endpoint=http://127.0.0.1:1",
})
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class LarkSourceApiTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    // The selection processor needs the concrete service, so the mock replaces that bean rather than the port.
    @MockitoBean
    private DefaultLarkSourceService sources;

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
                        WHERE issuer = :issuer AND subject = 'lark-source-owner'
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

    private static final UUID SOURCE = UUID.fromString("7f3d2c1a-0b9e-4d8c-a7f6-5e4d3c2b1a09");
    private static final UUID CREDENTIAL = UUID.fromString("2a1b0c9d-8e7f-4a6b-9c5d-4e3f2a1b0c9d");

    private static SourceOperationView operation(SourceOperationType type) {
        return new SourceOperationView(new SourceOperationId(UUID.randomUUID()), type,
                SourceOperationStatus.NOT_STARTED, Instant.now(), null, null);
    }

    private static SelectionReceipt receipt() {
        return new SelectionReceipt(new SourceId(SOURCE), operation(SourceOperationType.VALIDATE_LARK_SELECTION));
    }

    private static Configuration configuration() {
        return new Configuration(new SourceId(SOURCE), new CredentialId(CREDENTIAL), "Lark app", "ACTIVE", 2L, 4L, 2L,
                30, 7L, false, null, false, null, null);
    }

    private static String scope() {
        return """
                {"roots":[{"kind":"WIKI_SPACE","value":"7695460898437074458"},
                  {"kind":"DRIVE_FOLDER","value":"https://tenant.larksuite.com/drive/folder/fldDriveTest01"}],
                 "syncIntervalMinutes":30}""";
    }

    private static String createBody() {
        return """
                {"requestId":"%s","name":"Lark","credentialId":"%s","scope":%s,"access":"PRIVATE"}
                """.formatted(UUID.randomUUID(), CREDENTIAL, scope());
    }

    @Test
    void creatingASourceAcceptsTheScopeForVerification() throws Exception {
        var passed = new Scope[1];
        when(sources.create(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            passed[0] = call.getArgument(4);
            assertEquals(SourceAccess.PRIVATE, call.getArgument(5));
            return receipt();
        });

        mockMvc.perform(post("/api/sources/lark")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()))
                .andExpect(jsonPath("$.operation.type").value("VALIDATE_LARK_SELECTION"));
        assertEquals(new Scope(List.of(new Root(RootKind.WIKI_SPACE, "7695460898437074458"),
                new Root(RootKind.DRIVE_FOLDER, "https://tenant.larksuite.com/drive/folder/fldDriveTest01")), 30),
                passed[0]);
    }

    @Test
    void aMalformedScopeIsRefusedBeforeTheService() throws Exception {
        for (String scope : List.of(
                "{\"roots\":[],\"syncIntervalMinutes\":30}",
                "{\"roots\":[{\"kind\":\"SITE\",\"value\":\"x\"}],\"syncIntervalMinutes\":30}",
                "{\"roots\":[{\"kind\":\"WIKI_SPACE\",\"value\":\"\"}],\"syncIntervalMinutes\":30}",
                "{\"roots\":[{\"kind\":\"WIKI_SPACE\",\"value\":\"7695460898437074458\"}],\"syncIntervalMinutes\":0}")) {
            mockMvc.perform(post("/api/sources/lark")
                            .with(authentication(owner))
                            .header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"%s\",\"name\":\"Lark\",\"credentialId\":\"%s\",\"scope\":%s,\"access\":\"PUBLIC\"}"
                                    .formatted(UUID.randomUUID(), CREDENTIAL, scope)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(sources);
    }

    @Test
    void replacingTheScopeNeedsTheRevision() throws Exception {
        when(sources.replaceScope(any(), any(), any(), anyLong(), anyLong(), any())).thenReturn(receipt());
        String body = "{\"requestId\":\"%s\",\"expectedCredentialRevision\":2,\"scope\":%s}"
                .formatted(UUID.randomUUID(), scope());

        mockMvc.perform(put("/api/sources/{id}/lark/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/sources/{id}/lark/scope", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));
        verify(sources).replaceScope(any(), any(), eq(new SourceId(SOURCE)), eq(3L), eq(2L), any());
    }

    @Test
    void configurationRootsSpacesAndReceiptAreReadable() throws Exception {
        when(sources.configuration(any(), any())).thenReturn(configuration());
        when(sources.roots(any(), any())).thenReturn(List.of(
                new RootView(RootKind.WIKI_SPACE, "7695460898437074458", "MemoryOS Test"),
                new RootView(RootKind.DRIVE_FOLDER, "fldDriveTest01", null)));
        when(sources.spaces(any(), eq(new CredentialId(CREDENTIAL)))).thenReturn(List.of(
                new SpaceOption("7695460898437074458", "MemoryOS Test")));
        when(sources.selectionRequest(any(), any())).thenReturn(receipt());

        mockMvc.perform(get("/api/sources/{id}/lark", SOURCE).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.rootCount").value(2))
                .andExpect(jsonPath("$.syncIntervalMinutes").value(30))
                .andExpect(jsonPath("$.credentialName").value("Lark app"));
        mockMvc.perform(get("/api/sources/{id}/lark/roots", SOURCE).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("WIKI_SPACE"))
                .andExpect(jsonPath("$[0].displayName").value("MemoryOS Test"))
                .andExpect(jsonPath("$[1].token").value("fldDriveTest01"));
        mockMvc.perform(get("/api/sources/lark/credentials/{id}/spaces", CREDENTIAL).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].spaceId").value("7695460898437074458"))
                .andExpect(jsonPath("$[0].name").value("MemoryOS Test"));
        mockMvc.perform(get("/api/sources/lark/selection-requests/{id}", UUID.randomUUID()).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value(SOURCE.toString()));
        mockMvc.perform(get("/api/sources/{id}/lark", SOURCE)).andExpect(status().isUnauthorized());
    }

    @Test
    void scheduleAndPauseCarryTheScheduleRevision() throws Exception {
        when(sources.updateSchedule(any(), any(), anyLong(), anyInt())).thenReturn(configuration());
        when(sources.setPaused(any(), any(), anyLong(), anyBoolean())).thenReturn(configuration());

        mockMvc.perform(put("/api/sources/{id}/lark/schedule", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncIntervalMinutes\":60}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""));
        verify(sources).updateSchedule(any(), any(), eq(7L), eq(60));
        mockMvc.perform(post("/api/sources/{id}/lark/pause", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":7,\"paused\":true}"))
                .andExpect(status().isOk());
        verify(sources).setPaused(any(), any(), eq(7L), eq(true));
    }

    @Test
    void synchronizeSchedulesARun() throws Exception {
        when(sources.synchronize(any(), any())).thenReturn(operation(SourceOperationType.SYNC_SOURCE));
        mockMvc.perform(post("/api/sources/{id}/lark/sync", SOURCE)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("SYNC_SOURCE"));
    }
}
