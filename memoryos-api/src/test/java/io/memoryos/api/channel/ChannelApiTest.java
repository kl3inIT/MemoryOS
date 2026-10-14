package io.memoryos.api.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelConnectionService;
import io.memoryos.channel.ChannelConnectionService.Listed;
import io.memoryos.channel.ChannelConnectionState;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelRegion;
import io.memoryos.channel.ChannelVerification;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.IdentityContext;
import io.memoryos.shared.ActorId;
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
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000246",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000246",
        "memoryos.initial-tenant.owner-subject=channels-owner",
        "memoryos.initial-tenant.slug=channels",
        "memoryos.initial-tenant.display-name=Channels",
        "memoryos.initial-tenant.change-reference=MEM-246-TEST",
        "memoryos.search.endpoint=http://127.0.0.1:1",
})
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class ChannelApiTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();
    private static final String SECRET = "lark-app-secret-for-the-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private ChannelConnectionService channels;

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
        UUID memberActorId = UUID.fromString("6c49f9ee-7d53-42aa-b4a3-8a53919df4b0");
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
        return jdbcClient.sql("SELECT id FROM tenants WHERE slug = 'channels'")
                .query(UUID.class)
                .single();
    }

    private UUID ownerActorId() {
        return jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'channels-owner'
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

    private static Listed listed(UUID id, boolean enabled, Listed.Status status) {
        var bot = new ChannelConnection(id, ChannelProvider.LARK, "Trợ lý", ChannelRegion.LARK, "cli_a1b2c3d4e5f6", "vi",
                enabled, new ActorId(UUID.randomUUID()), 3, Instant.now(), Instant.now(), null, "MemoryOS Bot", "ou_bot",
                List.of("contact:user.email:readonly"));
        var state = new ChannelConnectionState(Instant.now(), null, null, null, Instant.now());
        return new Listed(bot, state, status);
    }

    @Test
    void theListNeedsTheCapabilityAndCarriesTheStateButNoSecret() throws Exception {
        var id = UUID.randomUUID();
        when(channels.listWithState(any())).thenReturn(List.of(listed(id, true, Listed.Status.CONNECTED)));
        mockMvc.perform(get("/api/channels").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].status").value("CONNECTED"))
                .andExpect(jsonPath("$[0].botName").value("MemoryOS Bot"))
                .andExpect(jsonPath("$[0].missingScopes[0]").value("contact:user.email:readonly"))
                .andExpect(jsonPath("$[0].revision").value(3))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].appSecret").doesNotExist());
        when(channels.listWithState(any())).thenThrow(new IamException(IamFailureReason.ACCESS_DENIED, "no CHANNELS_MANAGE"));
        mockMvc.perform(get("/api/channels").with(authentication(member))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/channels")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTestChecksTheAppAndARejectedAppIsABadRequest() throws Exception {
        String body = """
                {"region":"LARK","appId":"cli_a1b2c3d4e5f6","appSecret":"%s"}
                """.formatted(SECRET);
        when(channels.test(any(), eq(ChannelProvider.LARK), eq(ChannelRegion.LARK), eq("cli_a1b2c3d4e5f6"), eq(SECRET)))
                .thenReturn(new ChannelVerification("MemoryOS Bot", "ou_bot", Set.of("im:chat:readonly")));
        mockMvc.perform(post("/api/channels/lark/test").with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/channels/lark/test").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botName").value("MemoryOS Bot"))
                .andExpect(jsonPath("$.missingScopes[0]").value("im:chat:readonly"));

        when(channels.test(any(), any(), any(), any(), any())).thenThrow(ChannelException.rejected());
        mockMvc.perform(post("/api/channels/lark/test").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_APP_REJECTED"));
    }

    @Test
    void addingABotReturnsItCreated() throws Exception {
        var id = UUID.randomUUID();
        var created = listed(id, false, Listed.Status.OFF);
        when(channels.create(any(), any())).thenReturn(created.connection());
        when(channels.listWithState(any())).thenReturn(List.of(created));
        mockMvc.perform(post("/api/channels/lark").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Trợ lý","region":"LARK","appId":"cli_a1b2c3d4e5f6","appSecret":"%s","noticeLanguage":"vi"}
                                """.formatted(SECRET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("OFF"));
        verify(channels).create(any(), argThat(draft -> draft.provider() == ChannelProvider.LARK
                && draft.secret().equals(SECRET) && draft.noticeLanguage().equals("vi")));

        mockMvc.perform(post("/api/channels/lark").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Trợ lý","region":"LARK","appId":"cli_a1b2c3d4e5f6","appSecret":"%s","noticeLanguage":"fr"}
                                """.formatted(SECRET)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changesFollowTheRevision() throws Exception {
        var id = UUID.randomUUID();
        when(channels.listWithState(any())).thenReturn(List.of(listed(id, true, Listed.Status.CONNECTING)));
        mockMvc.perform(put("/api/channels/{id}/enabled", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/channels/{id}/enabled", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "not-a-revision")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_INVALID"));
        verify(channels, never()).setEnabled(any(), any(), eq(3L), eq(true));
        mockMvc.perform(put("/api/channels/{id}/enabled", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTING"));
        verify(channels).setEnabled(any(), eq(id), eq(3L), eq(true));

        mockMvc.perform(put("/api/channels/{id}/secret", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appSecret\":\"another-secret\"}"))
                .andExpect(status().isOk());
        verify(channels).replaceSecret(any(), eq(id), eq(3L), eq("another-secret"));

        when(channels.setEnabled(any(), eq(id), eq(2L), eq(false))).thenThrow(ChannelException.changed());
        mockMvc.perform(put("/api/channels/{id}/enabled", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_CHANGED"));

        mockMvc.perform(delete("/api/channels/{id}", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"3\""))
                .andExpect(status().isNoContent());
        verify(channels).delete(any(), eq(id), eq(3L));
    }
}
