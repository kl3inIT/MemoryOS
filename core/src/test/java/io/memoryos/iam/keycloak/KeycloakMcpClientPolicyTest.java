package io.memoryos.iam.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.iam.McpClientPolicy;
import io.memoryos.iam.McpClientPolicyException;
import io.memoryos.iam.McpClientPolicyFailureReason;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

/**
 * The trusted hosts and the removal of clients against a stand-in for Keycloak's admin API. The profile and policy
 * shapes are the realm script's, as Keycloak 26.8 returns them; what is checked is what MemoryOS writes and leaves.
 */
class KeycloakMcpClientPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROFILES = """
            {"profiles":[
              {"name":"partner-profile","executors":[{"executor":"secure-session","configuration":{}}]},
              {"name":"memoryos-mcp-cimd","description":"MEM-114","executors":[{"executor":"memoryos-client-id-metadata-document",
                "configuration":{"cimd-allow-http-scheme":false,"cimd-allow-permitted-domains":["claude.ai","chatgpt.com"],
                "cimd-resource-indicator-allow-list":["https://memoryos.example.test/mcp"]}}]}]}
            """;
    private static final String POLICIES = """
            {"policies":[
              {"name":"memoryos-mcp-cimd","enabled":true,"profiles":["memoryos-mcp-cimd"],"conditions":[
                {"condition":"client-id-uri","configuration":{"client-id-uri-scheme":["https"],
                  "client-id-uri-allow-permitted-domains":["claude.ai","chatgpt.com"]}}]},
              {"name":"partner-policy","enabled":true,"profiles":["partner-profile"],"conditions":[]}]}
            """;
    private static final String CLIENTS = """
            [{"id":"c-web","clientId":"memoryos-web"},
             {"id":"c-claude","clientId":"https://claude.ai/oauth/mcp-oauth-client-metadata"},
             {"id":"c-chatgpt","clientId":"https://chatgpt.com/oauth/client.json"},
             {"id":"c-agent","clientId":"https://agent.example.com/client.json"},
             {"id":"c-sub","clientId":"https://eu.tools.example.org/client.json"}]
            """;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> written = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsTheHostsOfTheMetadataDocumentProfileAndPolicy() {
        try (var keycloak = keycloak(serverUrl())) {
            var hosts = new KeycloakMcpClientPolicy(keycloak, "memoryos").trusted();
            assertEquals(Set.of("claude.ai", "chatgpt.com"), hosts.clientIdHosts());
            assertEquals(Set.of("claude.ai", "chatgpt.com"), hosts.documentHosts());
        }
    }

    @Test
    void writesOnlyItsOwnHostsAndKeepsEveryOtherSetting() throws IOException {
        try (var keycloak = keycloak(serverUrl())) {
            new KeycloakMcpClientPolicy(keycloak, "memoryos").trust(new McpClientPolicy.Hosts(
                    Set.of("claude.ai", "agent.example.com"), Set.of("claude.ai", "agent.example.com", "localhost")));
        }
        JsonNode profiles = JSON.readTree(written.get("profiles"));
        assertEquals("partner-profile", profiles.path("profiles").get(0).path("name").asText());
        JsonNode configuration = profiles.path("profiles").get(1).path("executors").get(0).path("configuration");
        assertEquals("[\"agent.example.com\",\"claude.ai\",\"localhost\"]", configuration.path("cimd-allow-permitted-domains").toString());
        assertEquals("[\"https://memoryos.example.test/mcp\"]", configuration.path("cimd-resource-indicator-allow-list").toString());
        assertFalse(configuration.path("cimd-allow-http-scheme").asBoolean(true));

        JsonNode policies = JSON.readTree(written.get("policies"));
        JsonNode condition = policies.path("policies").get(0).path("conditions").get(0).path("configuration");
        assertEquals("[\"agent.example.com\",\"claude.ai\"]", condition.path("client-id-uri-allow-permitted-domains").toString());
        assertEquals("[\"https\"]", condition.path("client-id-uri-scheme").toString());
        assertEquals("partner-policy", policies.path("policies").get(1).path("name").asText());
    }

    @Test
    void anUnchangedListWritesNothing() {
        try (var keycloak = keycloak(serverUrl())) {
            new KeycloakMcpClientPolicy(keycloak, "memoryos").trust(new McpClientPolicy.Hosts(
                    Set.of("claude.ai", "chatgpt.com"), Set.of("claude.ai", "chatgpt.com")));
        }
        assertTrue(requests.stream().noneMatch(request -> request.startsWith("PUT")), requests::toString);
    }

    @Test
    void removesOnlyTheDocumentClientsOfHostsNoLongerTrusted() {
        int removed;
        try (var keycloak = keycloak(serverUrl())) {
            removed = new KeycloakMcpClientPolicy(keycloak, "memoryos").removeClientsOutside(new McpClientPolicy.Hosts(
                    Set.of("claude.ai", "*.example.org"), Set.of()));
        }
        // ChatGPT and the agent go; Claude stays, a subdomain stays under its wildcard, and Keycloak's own clients are
        // never touched.
        assertEquals(2, removed);
        assertEquals(List.of("DELETE /admin/realms/memoryos/clients/c-chatgpt", "DELETE /admin/realms/memoryos/clients/c-agent"),
                requests.stream().filter(request -> request.startsWith("DELETE")).toList());
    }

    @Test
    void withoutItsAccountItIsNotConfiguredAndAnUnreachableKeycloakIsUnavailable() {
        assertFalse(new KeycloakMcpClientPolicy(null, "memoryos").configured());
        try (var keycloak = keycloak("http://127.0.0.1:1")) {
            var failure = assertThrows(McpClientPolicyException.class,
                    () -> new KeycloakMcpClientPolicy(keycloak, "memoryos").trusted());
            assertEquals(McpClientPolicyFailureReason.UNAVAILABLE.code(), failure.code());
        }
    }

    private static Keycloak keycloak(String serverUrl) {
        return KeycloakAdminConfiguration.client(new KeycloakAdminProperties(serverUrl, "memoryos",
                "memoryos-user-provisioner", "provisioner-secret", "memoryos-web",
                "https://memoryos.example.test/invite/activate", Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2)), "memoryos-mcp-admin", "mcp-admin-secret");
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        requests.add(method + " " + path);
        switch (path) {
            case "/realms/memoryos/protocol/openid-connect/token" -> respond(exchange, 200, """
                    {"access_token":"admin-token","expires_in":300,"refresh_expires_in":0,"token_type":"Bearer","scope":""}
                    """);
            case "/admin/realms/memoryos/client-policies/profiles" -> {
                if (method.equals("PUT")) {
                    written.put("profiles", body);
                    noContent(exchange);
                } else {
                    respond(exchange, 200, PROFILES);
                }
            }
            case "/admin/realms/memoryos/client-policies/policies" -> {
                if (method.equals("PUT")) {
                    written.put("policies", body);
                    noContent(exchange);
                } else {
                    respond(exchange, 200, POLICIES);
                }
            }
            case "/admin/realms/memoryos/clients" -> respond(exchange, 200, CLIENTS);
            default -> {
                if (method.equals("DELETE") && path.startsWith("/admin/realms/memoryos/clients/")) {
                    noContent(exchange);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                }
            }
        }
    }

    private static void noContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
