package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.json.JsonMapper;

class McpOAuthProtocolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<String, Consumer<HttpExchange>> routes = new ConcurrentHashMap<>();
    private final McpOAuthProtocol protocol = new McpOAuthProtocol(Duration.ofSeconds(2), Duration.ofSeconds(5));
    private HttpServer server;
    private String origin;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            var route = routes.get(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (route == null) respond(exchange, 404, null);
            else route.accept(exchange);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void discoversThroughTheChallengeAndPrefersRfc8414PathInsertion() {
        routes.put("POST /mcp", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer error=\"invalid_token\", resource_metadata=\""
                    + origin + "/.well-known/oauth-protected-resource/mcp\", scope=\"files:read files:write\"");
            respond(exchange, 401, null);
        });
        routes.put("GET /.well-known/oauth-protected-resource/mcp", exchange -> respond(exchange, 200, Map.of(
                "resource", origin + "/mcp", "authorization_servers", List.of(origin + "/tenant1"),
                "scopes_supported", List.of("files:read"))));
        routes.put("GET /.well-known/oauth-authorization-server/tenant1", exchange -> respond(exchange, 200, metadata(origin + "/tenant1")));
        routes.put("GET /.well-known/openid-configuration/tenant1", exchange -> respond(exchange, 500, null));

        var discovery = protocol.discover(origin + "/mcp");

        assertEquals("files:read files:write", discovery.challengedScope());
        assertEquals(List.of("files:read"), discovery.protectedResource().scopesSupported());
        var authorizationServer = discovery.authorizationServers().getFirst();
        assertEquals(origin + "/tenant1", authorizationServer.issuer());
        assertEquals(URI.create(origin + "/authorize?tenant=1"), authorizationServer.authorizationEndpoint());
        assertEquals(URI.create(origin + "/register"), authorizationServer.registrationEndpoint());
        assertTrue(authorizationServer.clientIdMetadataDocumentSupported());
        assertTrue(authorizationServer.issParameterSupported());
    }

    @Test
    void fallsBackToRootProtectedResourceAndOidcPathAppending() {
        routes.put("POST /mcp", exchange -> respond(exchange, 405, null));
        // The protected-resource metadata adds a trailing slash; the stored issuer is the metadata's own value.
        routes.put("GET /.well-known/oauth-protected-resource", exchange -> respond(exchange, 200, Map.of(
                "resource", origin + "/mcp/", "authorization_servers", List.of(origin + "/tenant1/"))));
        routes.put("GET /tenant1/.well-known/openid-configuration", exchange -> respond(exchange, 200, metadata(origin + "/tenant1")));

        var discovery = protocol.discover(origin + "/mcp");

        assertNull(discovery.challengedScope());
        assertEquals(URI.create(origin + "/.well-known/oauth-protected-resource"), discovery.protectedResource().metadataUrl());
        assertEquals(origin + "/tenant1", discovery.authorizationServers().getFirst().issuer());
    }

    @Test
    void refusesMetadataThatDoesNotMatchOrLacksPkce() {
        routes.put("POST /mcp", exchange -> respond(exchange, 401, null));
        routes.put("GET /.well-known/oauth-protected-resource/mcp", exchange -> respond(exchange, 200, Map.of(
                "resource", origin + "/other", "authorization_servers", List.of(origin))));
        assertCode("MCP_OAUTH_DISCOVERY_FAILED", () -> protocol.discover(origin + "/mcp"));

        routes.put("GET /.well-known/oauth-protected-resource/mcp", exchange -> respond(exchange, 200, Map.of(
                "resource", origin + "/mcp", "authorization_servers", List.of(origin))));
        var withoutPkce = new java.util.HashMap<>(metadata(origin));
        withoutPkce.remove("code_challenge_methods_supported");
        routes.put("GET /.well-known/oauth-authorization-server", exchange -> respond(exchange, 200, withoutPkce));
        assertCode("MCP_OAUTH_DISCOVERY_FAILED", () -> protocol.discover(origin + "/mcp"));

        routes.put("GET /.well-known/oauth-authorization-server", exchange -> respond(exchange, 200, metadata("https://evil.example")));
        assertCode("MCP_OAUTH_DISCOVERY_FAILED", () -> protocol.discover(origin + "/mcp"));
    }

    @Test
    void doesNotFollowRedirectsDuringDiscovery() {
        routes.put("POST /mcp", exchange -> respond(exchange, 401, null));
        routes.put("GET /.well-known/oauth-protected-resource/mcp", exchange -> {
            exchange.getResponseHeaders().add("Location", origin + "/moved");
            respond(exchange, 302, null);
        });
        routes.put("GET /moved", exchange -> respond(exchange, 200, Map.of(
                "resource", origin + "/mcp", "authorization_servers", List.of(origin))));

        assertCode("MCP_OAUTH_DISCOVERY_FAILED", () -> protocol.discover(origin + "/mcp"));
    }

    @Test
    void registersWithTheRedirectUriAndReturnsTheIssuedClient() {
        var received = new AtomicReference<Map<?, ?>>();
        routes.put("POST /register", exchange -> {
            received.set(JSON.readValue(read(exchange), Map.class));
            respond(exchange, 201, Map.of("client_id", "registered-client", "client_secret", "registered-secret",
                    "token_endpoint_auth_method", "client_secret_post", "registration_access_token", "rat"));
        });

        var registration = protocol.register(authorizationServer(List.of("client_secret_post")),
                URI.create("https://memoryos.example/login/oauth2/code/mcp"), "MemoryOS");

        assertEquals(List.of("https://memoryos.example/login/oauth2/code/mcp"), received.get().get("redirect_uris"));
        assertEquals("client_secret_post", received.get().get("token_endpoint_auth_method"));
        assertEquals("registered-client", registration.client().clientId());
        assertEquals(McpTokenEndpointAuthMethod.CLIENT_SECRET_POST, registration.client().method());
        assertEquals("rat", registration.registrationAccessToken());
        assertFalse(registration.toString().contains("registered-secret"));
    }

    @Test
    void exchangesTheCodeWithPkceResourceAndBasicClientAuthentication() {
        var form = new AtomicReference<Map<String, String>>();
        var authorization = new AtomicReference<String>();
        routes.put("POST /token", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            form.set(query(read(exchange)));
            respond(exchange, 200, Map.of("access_token", "access", "refresh_token", "refresh", "token_type", "Bearer",
                    "expires_in", 3600, "scope", "files:read"));
        });
        var client = new McpOAuthProtocol.Client("client id", "secret:with+chars", McpTokenEndpointAuthMethod.CLIENT_SECRET_BASIC);

        var tokens = protocol.exchangeCode(URI.create(origin + "/token"), client, "the-code",
                URI.create("https://memoryos.example/login/oauth2/code/mcp"), "verifier", origin + "/mcp");

        assertEquals("authorization_code", form.get().get("grant_type"));
        assertEquals("verifier", form.get().get("code_verifier"));
        assertEquals(origin + "/mcp", form.get().get("resource"));
        assertNull(form.get().get("client_secret"));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("client+id:secret%3Awith%2Bchars".getBytes(StandardCharsets.UTF_8)),
                authorization.get());
        assertEquals("access", tokens.accessToken());
        assertEquals("refresh", tokens.refreshToken());
        assertNotNull(tokens.expiresAt());
        assertTrue(tokens.expiresAt().isAfter(Instant.now().plusSeconds(3500)));
        assertFalse(tokens.toString().contains("access"));
    }

    @Test
    void invalidGrantRequiresAuthorizationAndOtherFailuresStayGeneric() {
        var client = new McpOAuthProtocol.Client("public", null, McpTokenEndpointAuthMethod.NONE);
        routes.put("POST /token", exchange -> respond(exchange, 400, Map.of("error", "invalid_grant", "error_description", "upstream detail")));
        var required = assertThrows(McpException.class, () -> protocol.refresh(URI.create(origin + "/token"), client, "refresh", origin + "/mcp"));
        assertEquals("MCP_AUTHORIZATION_REQUIRED", required.code());
        assertFalse(required.safeMessage().contains("upstream"));

        routes.put("POST /token", exchange -> respond(exchange, 200, Map.of("access_token", "a", "token_type", "mac")));
        assertCode("MCP_OAUTH_TOKEN_FAILED", () -> protocol.refresh(URI.create(origin + "/token"), client, "refresh", origin + "/mcp"));
        routes.put("POST /token", exchange -> respond(exchange, 500, null));
        assertCode("MCP_OAUTH_TOKEN_FAILED", () -> protocol.refresh(URI.create(origin + "/token"), client, "refresh", origin + "/mcp"));
    }

    @Test
    void buildsAuthorizationUrlsAndParsesChallengesAndProbeOrder() {
        var url = protocol.authorizationUrl(URI.create("https://as.example/authorize?tenant=1"), "client", URI.create("https://m.example/cb"),
                "state", "challenge", "files:read", "https://mcp.example/mcp", Map.of("hd", "tasco.vn", "state", "attacker"));
        var parameters = query(url.getRawQuery());
        assertEquals("1", parameters.get("tenant"));
        assertEquals("state", parameters.get("state"));
        assertEquals("S256", parameters.get("code_challenge_method"));
        assertEquals("https://mcp.example/mcp", parameters.get("resource"));
        assertEquals("tasco.vn", parameters.get("hd"));

        var challenge = McpOAuthProtocol.challenge(List.of("Basic realm=\"x\"", "Bearer realm=\"a,b\", scope=\"read write\", resource_metadata=\"https://m.example/.well-known/oauth-protected-resource\""));
        assertEquals("read write", challenge.scope());
        assertEquals(URI.create("https://m.example/.well-known/oauth-protected-resource"), challenge.resourceMetadata());

        assertEquals(List.of(URI.create("https://a.example/.well-known/oauth-authorization-server/t1"),
                        URI.create("https://a.example/.well-known/openid-configuration/t1"),
                        URI.create("https://a.example/t1/.well-known/openid-configuration")),
                McpOAuthProtocol.authorizationServerMetadataUrls(URI.create("https://a.example/t1")));
        assertEquals("https://mcp.example/mcp", McpOAuthProtocol.canonicalResource("HTTPS://MCP.example/mcp/"));
    }

    private Map<String, Object> metadata(String issuer) {
        return Map.of("issuer", issuer, "authorization_endpoint", origin + "/authorize?tenant=1", "token_endpoint", origin + "/token",
                "registration_endpoint", origin + "/register", "code_challenge_methods_supported", List.of("S256"),
                "client_id_metadata_document_supported", true, "authorization_response_iss_parameter_supported", true);
    }

    private McpOAuthProtocol.AuthorizationServer authorizationServer(List<String> methods) {
        return new McpOAuthProtocol.AuthorizationServer(origin, URI.create(origin + "/authorize"), URI.create(origin + "/token"),
                URI.create(origin + "/register"), null, methods, false, false);
    }

    private static void respond(HttpExchange exchange, int status, Object body) {
        try (exchange) {
            byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
            if (body != null) exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String read(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Map<String, String> query(String raw) {
        var result = new java.util.LinkedHashMap<String, String>();
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            result.putIfAbsent(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void assertCode(String code, Executable action) {
        assertEquals(code, assertThrows(McpException.class, action).code());
    }
}
