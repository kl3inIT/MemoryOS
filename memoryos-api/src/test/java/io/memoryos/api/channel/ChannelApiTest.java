package io.memoryos.api.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import io.memoryos.channel.ChannelConnectionService.Answering;
import io.memoryos.channel.ChannelConnectionService.Group;
import io.memoryos.channel.ChannelConnectionService.Listed;
import io.memoryos.channel.ChannelConnectionState;
import io.memoryos.channel.ChannelCredentials;
import io.memoryos.channel.ChannelDelivery;
import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelGroup;
import io.memoryos.channel.ChannelLink;
import io.memoryos.channel.ChannelLinkCode;
import io.memoryos.channel.ChannelLinkService;
import io.memoryos.channel.ChannelPairing;
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
import java.util.Map;
import java.util.Optional;
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

    @MockitoBean
    private ChannelLinkService links;

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
        var bot = new ChannelConnection(id, ChannelProvider.LARK, "Trợ lý", "cli_a1b2c3d4e5f6",
                Map.of(ChannelRegion.SETTING, "LARK"), "vi",
                enabled, new ActorId(UUID.randomUUID()), 3, Instant.now(), Instant.now(), null, "MemoryOS Bot", "ou_bot",
                List.of("contact:user.email:readonly"));
        var state = new ChannelConnectionState(Instant.now(), null, null, null, Instant.now(), false);
        return new Listed(bot, state, status, null, null);
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

    /** MEM-273: a bot answers with the assistant it is bound to, which its assistant's page lists. */
    @Test
    void aBotIsBoundToAnAssistantAndTheAssistantListsItsBots() throws Exception {
        var id = UUID.randomUUID();
        var hr = UUID.randomUUID();
        var plain = listed(id, true, Listed.Status.CONNECTED);
        var bot = new ChannelConnection(id, ChannelProvider.LARK, "Trợ lý", "cli_a1b2c3d4e5f6",
                Map.of(ChannelRegion.SETTING, "LARK"), "vi", true, new ActorId(UUID.randomUUID()), 3, Instant.now(),
                Instant.now(), null, "MemoryOS Bot", "ou_bot", List.of());
        when(channels.bindPersona(any(), eq(id), eq(hr))).thenReturn(bot);
        when(channels.listWithState(any())).thenReturn(List.of(new Listed(bot, plain.state(), Listed.Status.CONNECTED, hr, "Nhân sự")));
        mockMvc.perform(put("/api/channels/{id}/persona", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"personaId\":\"%s\"}".formatted(hr)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistant.id").value(hr.toString()))
                .andExpect(jsonPath("$.assistant.name").value("Nhân sự"));

        when(channels.bindPersona(any(), eq(id), eq(null))).thenThrow(ChannelException.assistantNotEditable());
        mockMvc.perform(put("/api/channels/{id}/persona", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"personaId\":null}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANNEL_ASSISTANT_NOT_EDITABLE"));

        var group = new ChannelGroup("oc_hr", "Phòng Nhân sự", null, Instant.now(), Instant.now(), null);
        when(channels.boundTo(any(), eq(hr))).thenReturn(List.of(new Answering(bot, false, List.of(group))));
        mockMvc.perform(get("/api/chat/personas/{id}/channels", hr).with(authentication(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].provider").value("LARK"))
                .andExpect(jsonPath("$[0].botName").value("MemoryOS Bot"))
                .andExpect(jsonPath("$[0].byDefault").value(false))
                .andExpect(jsonPath("$[0].groups[0].id").value("oc_hr"))
                .andExpect(jsonPath("$[0].groups[0].name").value("Phòng Nhân sự"))
                .andExpect(jsonPath("$[0].appId").doesNotExist())
                .andExpect(jsonPath("$[0].serviceAccountId").doesNotExist());
    }

    /** MEM-273: a group chat the bot answered in is listed, given its own assistant, named and forgotten. */
    @Test
    void aBotsGroupChatsAreListedGivenAnAssistantNamedAndForgotten() throws Exception {
        var id = UUID.randomUUID();
        var hr = UUID.randomUUID();
        var seen = Instant.parse("2026-10-17T02:00:00Z");
        var group = new ChannelGroup("oc_hr", "Phòng Nhân sự", "Nhân sự HN", seen, seen.plusSeconds(60), null);
        when(channels.groups(any(), eq(id))).thenReturn(List.of(new Group(group, hr, "Nhân sự", false)));
        mockMvc.perform(get("/api/channels/{id}/groups", id).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("oc_hr"))
                .andExpect(jsonPath("$[0].label").value("Nhân sự HN"))
                .andExpect(jsonPath("$[0].name").value("Phòng Nhân sự"))
                .andExpect(jsonPath("$[0].lastMessageAt").value("2026-10-17T02:01:00Z"))
                .andExpect(jsonPath("$[0].assistant.id").value(hr.toString()))
                .andExpect(jsonPath("$[0].assistant.name").value("Nhân sự"))
                .andExpect(jsonPath("$[0].permission").value("NOT_NEEDED"));

        mockMvc.perform(put("/api/channels/{id}/groups/{group}/persona", id, "oc_hr").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personaId\":\"%s\"}".formatted(hr)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistant.name").value("Nhân sự"));
        verify(channels).bindGroupPersona(any(), eq(id), eq("oc_hr"), eq(hr));

        doThrow(ChannelException.groupNotFound()).when(channels)
                .bindGroupPersona(any(), eq(id), eq("oc_gone"), any());
        mockMvc.perform(put("/api/channels/{id}/groups/{group}/persona", id, "oc_gone").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("{\"personaId\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_GROUP_NOT_FOUND"));

        mockMvc.perform(put("/api/channels/{id}/groups/{group}/label", id, "oc_hr").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Nhân sự HN\"}"))
                .andExpect(status().isOk());
        verify(channels).labelGroup(any(), eq(id), eq("oc_hr"), eq("Nhân sự HN"));
        mockMvc.perform(put("/api/channels/{id}/groups/{group}/label", id, "oc_hr").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"%s\"}".formatted("x".repeat(101))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/channels/{id}/groups/{group}", id, "oc_hr").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isNoContent());
        verify(channels).forgetGroup(any(), eq(id), eq("oc_hr"));
    }

    /** MEM-285: a Zalo group waits for permission, is allowed through the API, and a Lark group refuses the call. */
    @Test
    void aGroupThatNeedsPermissionIsAllowedThroughTheApi() throws Exception {
        var id = UUID.randomUUID();
        var seen = Instant.parse("2026-10-20T02:00:00Z");
        var waiting = new ChannelGroup("zg_1", null, null, seen, null, null);
        var allowed = new ChannelGroup("zg_1", null, null, seen, null, seen.plusSeconds(60));
        when(channels.groups(any(), eq(id))).thenReturn(List.of(new Group(waiting, null, null, true)),
                List.of(new Group(allowed, null, null, true)));
        mockMvc.perform(get("/api/channels/{id}/groups", id).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].permission").value("WAITING"));

        mockMvc.perform(put("/api/channels/{id}/groups/{group}/permission", id, "zg_1").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("ALLOWED"));
        verify(channels).allowGroup(any(), eq(id), eq("zg_1"), eq(true));

        doThrow(ChannelException.invalid("This bot answers in a group without an administrator's permission."))
                .when(channels).allowGroup(any(), eq(id), eq("oc_hr"), eq(true));
        mockMvc.perform(put("/api/channels/{id}/groups/{group}/permission", id, "oc_hr").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_INVALID"));
    }

    @Test
    void aTestChecksTheAppAndARejectedAppIsABadRequest() throws Exception {
        String body = """
                {"region":"LARK","appId":"cli_a1b2c3d4e5f6","appSecret":"%s"}
                """.formatted(SECRET);
        when(channels.test(any(), eq(ChannelProvider.LARK), eq(new ChannelCredentials("cli_a1b2c3d4e5f6", SECRET,
                Map.of(ChannelRegion.SETTING, "LARK")))))
                .thenReturn(new ChannelVerification("cli_a1b2c3d4e5f6", "MemoryOS Bot", "ou_bot", Set.of("im:chat:readonly")));
        mockMvc.perform(post("/api/channels/lark/test").with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/channels/lark/test").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botName").value("MemoryOS Bot"))
                .andExpect(jsonPath("$.missingScopes[0]").value("im:chat:readonly"));

        when(channels.test(any(), any(), any())).thenThrow(ChannelException.rejected());
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
                && draft.credentials().equals(new ChannelCredentials("cli_a1b2c3d4e5f6", SECRET, Map.of(ChannelRegion.SETTING, "LARK")))
                && draft.noticeLanguage().equals("vi")));

        mockMvc.perform(post("/api/channels/lark").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Trợ lý","region":"LARK","appId":"cli_a1b2c3d4e5f6","appSecret":"%s","noticeLanguage":"fr"}
                                """.formatted(SECRET)))
                .andExpect(status().isBadRequest());
    }

    /** MEM-283: a WhatsApp bot is its number, takes no secret, shows a code to scan and is linked again. */
    @Test
    void aWhatsAppBotIsAddedByItsNumberAndShowsItsCodeWhileItIsLinked() throws Exception {
        var id = UUID.randomUUID();
        var bot = new ChannelConnection(id, ChannelProvider.WHATSAPP, "Bot WhatsApp", "84912345678", Map.of(), "vi", false,
                new ActorId(UUID.randomUUID()), 1, Instant.now(), Instant.now(), null, "+84912345678",
                "84912345678@s.whatsapp.net", List.of());
        when(channels.create(any(), any())).thenReturn(bot);
        when(channels.relink(any(), eq(id), eq(1L))).thenReturn(bot);
        when(channels.listWithState(any())).thenReturn(List.of(new Listed(bot, ChannelConnectionState.NONE,
                Listed.Status.OFF, null, null)));

        mockMvc.perform(post("/api/channels/whatsapp").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Bot WhatsApp","phone":"+84 912 345 678","noticeLanguage":"vi"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("WHATSAPP"))
                .andExpect(jsonPath("$.appId").value("84912345678"))
                .andExpect(jsonPath("$.transport").doesNotExist());
        verify(channels).create(any(), argThat(draft -> draft.provider() == ChannelProvider.WHATSAPP
                && draft.credentials().equals(new ChannelCredentials("+84 912 345 678", "", Map.of()))));
        mockMvc.perform(post("/api/channels/whatsapp").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Bot WhatsApp","noticeLanguage":"vi"}
                                """))
                .andExpect(status().isBadRequest());

        when(channels.pairing(any(), eq(id))).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/channels/{id}/pairing", id).with(authentication(owner)))
                .andExpect(status().isNoContent());
        var expires = Instant.parse("2030-01-01T00:00:20Z");
        when(channels.pairing(any(), eq(id))).thenReturn(Optional.of(new ChannelPairing("2@abc", expires)));
        mockMvc.perform(get("/api/channels/{id}/pairing", id).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2@abc"))
                .andExpect(jsonPath("$.expiresAt").value("2030-01-01T00:00:20Z"));

        mockMvc.perform(post("/api/channels/{id}/pairing", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());
        verify(channels).relink(any(), eq(id), eq(1L));
    }

    /** MEM-263: a Zalo bot is its token and how it receives; it answers with no region and never the token. */
    @Test
    void aZaloBotIsAddedByItsTokenAndItsTransportChangesWithTheRevision() throws Exception {
        String token = "1234567890:zalo-token-for-the-test";
        var id = UUID.randomUUID();
        var bot = new ChannelConnection(id, ChannelProvider.ZALO_BOT, "Bot Zalo", "1234567890",
                Map.of(ChannelDelivery.SETTING, "WEBHOOK"), "vi", false, new ActorId(UUID.randomUUID()), 1, Instant.now(),
                Instant.now(), null, "Bot MemoryOS", "1234567890", List.of());
        var created = new Listed(bot, ChannelConnectionState.NONE, Listed.Status.OFF, null, null);
        when(channels.create(any(), any())).thenReturn(bot);
        when(channels.listWithState(any())).thenReturn(List.of(created));
        when(channels.test(any(), eq(ChannelProvider.ZALO_BOT), eq(new ChannelCredentials(null, token, Map.of()))))
                .thenReturn(new ChannelVerification("1234567890", "Bot MemoryOS", "1234567890", Set.of()));

        mockMvc.perform(post("/api/channels/zalo/test").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botName").value("Bot MemoryOS"));

        mockMvc.perform(post("/api/channels/zalo").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Bot Zalo","token":"%s","noticeLanguage":"vi","transport":"POLLING"}
                                """.formatted(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("ZALO_BOT"))
                .andExpect(jsonPath("$.transport").value("WEBHOOK"))
                .andExpect(jsonPath("$.region").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
        verify(channels).create(any(), argThat(draft -> draft.provider() == ChannelProvider.ZALO_BOT
                && draft.credentials().equals(new ChannelCredentials(null, token, Map.of(ChannelDelivery.SETTING, "POLLING")))));

        mockMvc.perform(post("/api/channels/zalo").with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Bot Zalo","token":"%s","noticeLanguage":"vi","transport":"CARRIER_PIGEON"}
                                """.formatted(token)))
                .andExpect(status().isBadRequest());

        when(channels.updateSettings(any(), eq(id), eq(1L), eq(Map.of(ChannelDelivery.SETTING, "WEBHOOK"))))
                .thenThrow(ChannelException.webUrlMissing());
        mockMvc.perform(put("/api/channels/{id}/transport", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"WEBHOOK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_WEB_URL_MISSING"));
        mockMvc.perform(put("/api/channels/{id}/transport", id).with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"POLLING\"}"))
                .andExpect(status().isOk());
        verify(channels).updateSettings(any(), eq(id), eq(1L), eq(Map.of(ChannelDelivery.SETTING, "POLLING")));
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

    @Test
    void aMemberMakesACodeSeesTheirLinksAndUnlinks() throws Exception {
        var bot = UUID.randomUUID();
        var linkedAt = Instant.parse("2026-10-15T07:00:00Z");
        when(links.links(any())).thenReturn(List.of(new ChannelLink(bot, "Zalo", ChannelProvider.LARK, linkedAt)));
        mockMvc.perform(get("/api/channel-links").with(authentication(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].connectionId").value(bot.toString()))
                .andExpect(jsonPath("$[0].botName").value("Zalo"))
                .andExpect(jsonPath("$[0].linkedAt").value("2026-10-15T07:00:00Z"));

        String body = "{\"connectionId\":\"" + bot + "\"}";
        when(links.code(any(), eq(bot))).thenReturn(new ChannelLinkCode("ABCD2345", Instant.parse("2026-10-15T07:10:00Z")));
        mockMvc.perform(post("/api/channel-links").with(authentication(member))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/channel-links").with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ABCD2345"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-15T07:10:00Z"));
        mockMvc.perform(post("/api/channel-links").with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        var other = UUID.randomUUID();
        when(links.code(any(), eq(other))).thenThrow(ChannelException.notFound());
        mockMvc.perform(post("/api/channel-links").with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connectionId\":\"" + other + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));

        mockMvc.perform(delete("/api/channel-links/{id}", bot).with(authentication(member)).header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isNoContent());
        verify(links).unlink(any(), eq(bot));
        mockMvc.perform(get("/api/channel-links")).andExpect(status().isUnauthorized());
    }
}
