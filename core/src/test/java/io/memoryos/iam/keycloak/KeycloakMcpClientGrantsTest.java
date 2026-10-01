package io.memoryos.iam.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.McpClientGrant;
import io.memoryos.iam.McpClientGrantException;
import io.memoryos.iam.McpClientGrantFailureReason;
import io.memoryos.shared.ActorId;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

/**
 * The consent calls against a stand-in for Keycloak's admin API. The consent shape and Keycloak's acceptance of an
 * encoded URL client ID were taken from a Keycloak 26.8 probe; what is checked here is what MemoryOS sends and keeps.
 */
class KeycloakMcpClientGrantsTest {
    private static final ActorId MEMBER = new ActorId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
    private static final String KEYCLOAK_USER = "6f1c2d3e-0000-4000-8000-000000000001";
    private static final String CLAUDE = "https://claude.ai/oauth/mcp-oauth-client-metadata";
    private static final String CONSENTS = """
            [{"clientId":"%s","createdDate":1790857772093,"lastUpdatedDate":1790857772113,
              "grantedClientScopes":["knowledge:read","offline_access"],
              "additionalGrants":[{"client":"d16513ec","key":"Offline Token"}]},
             {"clientId":"memoryos-chatgpt","createdDate":1790900000000,"lastUpdatedDate":1790900000000,
              "grantedClientScopes":["offline_access","knowledge:read"],"additionalGrants":[]},
             {"clientId":"https://tools.example.org/client.json","createdDate":1790800000000,
              "lastUpdatedDate":1790800000000,"grantedClientScopes":["knowledge:read"],"additionalGrants":[]},
             {"clientId":"memoryos-web","createdDate":1790000000000,"lastUpdatedDate":1790000000000,
              "grantedClientScopes":["profile","email"],"additionalGrants":[]}]
            """.formatted(CLAUDE);

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final ExternalIdentityResolver identities = mock(ExternalIdentityResolver.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        when(identities.identities(MEMBER)).thenReturn(List.of(
                new ExternalIdentity("https://accounts.example.org", "upstream-subject"),
                new ExternalIdentity("https://memoryos.example.test/realms/memoryos", KEYCLOAK_USER)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void listsOnlyGrantsThatCarryTheKnowledgeScopeNewestFirst() {
        try (var keycloak = keycloak(serverUrl())) {
            var grants = grants(keycloak).list(MEMBER);
            assertEquals(List.of(
                    new McpClientGrant("memoryos-chatgpt", McpClientGrant.Client.CHATGPT, "ChatGPT",
                            Instant.ofEpochMilli(1790900000000L)),
                    new McpClientGrant(CLAUDE, McpClientGrant.Client.CLAUDE, "Claude",
                            Instant.ofEpochMilli(1790857772093L)),
                    new McpClientGrant("https://tools.example.org/client.json", McpClientGrant.Client.OTHER,
                            "tools.example.org", Instant.ofEpochMilli(1790800000000L))), grants);
        }
        // Only the subject bound under this realm's issuer is asked about.
        assertEquals(List.of("GET /admin/realms/memoryos/users/" + KEYCLOAK_USER + "/consents"), adminRequests());
    }

    @Test
    void revokesClaudesGrantWithItsUrlClientIdAsOneEncodedSegment() {
        try (var keycloak = keycloak(serverUrl())) {
            grants(keycloak).revoke(MEMBER, CLAUDE);
        }
        var sent = adminRequests();
        assertEquals(2, sent.size(), sent::toString);
        assertEquals("GET /admin/realms/memoryos/users/" + KEYCLOAK_USER + "/consents", sent.get(0));
        String prefix = "DELETE /admin/realms/memoryos/users/" + KEYCLOAK_USER + "/consents/";
        assertTrue(sent.get(1).startsWith(prefix), sent::toString);
        // One path segment: every slash of the URL is encoded, and it decodes back to the client ID.
        String segment = sent.get(1).substring(prefix.length());
        assertFalse(segment.contains("/"), segment);
        assertEquals(CLAUDE, URLDecoder.decode(segment, UTF_8));
    }

    @Test
    void refusesToRevokeAClientTheMemberDidNotGrantTheKnowledgeScope() {
        try (var keycloak = keycloak(serverUrl())) {
            var refused = assertThrows(McpClientGrantException.class,
                    () -> grants(keycloak).revoke(MEMBER, "memoryos-web"));
            assertEquals(McpClientGrantFailureReason.NOT_FOUND.code(), refused.code());
        }
        assertTrue(adminRequests().stream().noneMatch(request -> request.startsWith("DELETE")), adminRequests()::toString);
    }

    @Test
    void aMemberWithoutAKeycloakBindingHasNoGrants() {
        when(identities.identities(MEMBER)).thenReturn(List.of());
        try (var keycloak = keycloak(serverUrl())) {
            assertEquals(List.of(), grants(keycloak).list(MEMBER));
        }
        assertEquals(List.of(), adminRequests());
    }

    @Test
    void anUnreachableKeycloakIsReportedAsUnavailable() {
        try (var keycloak = keycloak("http://127.0.0.1:1")) {
            var failure = assertThrows(McpClientGrantException.class, () -> grants(keycloak).list(MEMBER));
            assertEquals(McpClientGrantFailureReason.UNAVAILABLE.code(), failure.code());
        }
    }

    private KeycloakMcpClientGrants grants(Keycloak keycloak) {
        return new KeycloakMcpClientGrants(keycloak, properties(serverUrl()), identities);
    }

    private static Keycloak keycloak(String serverUrl) {
        return new KeycloakAdminConfiguration().keycloakAdminClient(properties(serverUrl));
    }

    private static KeycloakAdminProperties properties(String serverUrl) {
        return new KeycloakAdminProperties(serverUrl, "memoryos", "memoryos-user-provisioner", "provisioner-secret",
                "memoryos-web", "https://memoryos.example.test/invite/activate",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private List<String> adminRequests() {
        return requests.stream().filter(request -> request.contains(" /admin/")).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        exchange.getRequestBody().readAllBytes();
        requests.add(exchange.getRequestMethod() + " " + path);
        if (path.equals("/realms/memoryos/protocol/openid-connect/token")) {
            respond(exchange, 200, """
                    {"access_token":"admin-token","expires_in":300,"refresh_expires_in":0,"token_type":"Bearer","scope":""}
                    """);
        } else if (path.equals("/admin/realms/memoryos/users/" + KEYCLOAK_USER + "/consents")) {
            respond(exchange, 200, CONSENTS);
        } else if (path.startsWith("/admin/realms/memoryos/users/" + KEYCLOAK_USER + "/consents/")
                && exchange.getRequestMethod().equals("DELETE")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
