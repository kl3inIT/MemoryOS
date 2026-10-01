package io.memoryos.api.mcp.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.api.ApiPostgresDatabase;
import io.memoryos.api.security.BrowserMutation;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchPage;
import io.swagger.v3.core.util.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * MEM-114: the MCP endpoint through the real filter chains, with real signed tokens. Search itself is the retrieval
 * module's contract and is replaced here; what is tested is who gets in, what they are told, and what they can see.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000024";
    private static final String ENDPOINT = "http://localhost/mcp";
    private static final String API_AUDIENCE = "memoryos-api";
    private static final String OWNER = "startup-owner";
    private static final int CALLER_CALLS_PER_MINUTE = 3;
    private static final RSAKey SIGNING_KEY = rsaKey();
    private static final HttpServer JWK_SERVER = startJwkServer();
    private static final String ISSUER = "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private DocumentSearchService documents;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiPostgresDatabase.configure(registry);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("memoryos.identity.audience", () -> API_AUDIENCE);
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
        registry.add("memoryos.identity.keycloak.admin.client-secret", () -> "test-provisioner-secret");
        registry.add("memoryos.identity.keycloak.admin.action-redirect-uri", () -> "http://127.0.0.1/invite/activate");
        registry.add("spring.security.oauth2.client.registration.memoryos.client-secret", () -> "client-secret");
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.client.provider.memoryos.authorization-uri", () -> ISSUER + "/authorize");
        registry.add("spring.security.oauth2.client.provider.memoryos.token-uri", () -> ISSUER + "/token");
        registry.add("spring.security.oauth2.client.provider.memoryos.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("spring.security.oauth2.client.provider.memoryos.user-info-uri", () -> ISSUER + "/userinfo");
        registry.add("spring.security.oauth2.client.provider.memoryos.user-name-attribute", () -> "sub");
        registry.add("arconia.multitenancy.resolution.fixed.tenant-identifier", () -> TENANT);
        registry.add("memoryos.initial-tenant.id", () -> TENANT);
        registry.add("memoryos.initial-tenant.owner-subject", () -> OWNER);
        registry.add("memoryos.initial-tenant.slug", () -> "test");
        registry.add("memoryos.initial-tenant.display-name", () -> "Test");
        registry.add("memoryos.initial-tenant.change-reference", () -> "TEST-MCP-ENDPOINT");
        registry.add("memoryos.mcp.endpoint.url", () -> ENDPOINT);
        registry.add("memoryos.mcp.endpoint.caller-calls-per-minute", () -> CALLER_CALLS_PER_MINUTE);
    }

    @BeforeEach
    void endpointOn() {
        endpoint(true);
    }

    @AfterAll
    static void stopJwkServer() {
        JWK_SERVER.stop(0);
    }

    @Test
    void anOffSwitchHidesTheEndpointAndItsMetadata() throws Exception {
        endpoint(false);
        assertEquals(404, post(endpointToken(), initialize(), null).statusCode());
        assertEquals(404, get("/.well-known/oauth-protected-resource/mcp").statusCode());
    }

    @Test
    void anAnonymousCallIsToldWhereToSignIn() throws Exception {
        var response = post(null, initialize(), null);
        assertEquals(401, response.statusCode());
        String challenge = response.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertTrue(challenge.contains("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\""), challenge);
        assertTrue(challenge.contains("scope=\"knowledge:read\""), challenge);

        var metadata = json(get("/.well-known/oauth-protected-resource/mcp").body());
        assertEquals(ENDPOINT, metadata.path("resource").asText());
        assertEquals(ISSUER, metadata.path("authorization_servers").get(0).asText());
        assertEquals("knowledge:read", metadata.path("scopes_supported").get(0).asText());
    }

    @Test
    void onlyATokenIssuedForTheEndpointWithItsScopeGetsIn() throws Exception {
        // The browser API's token, a token without the scope, and the endpoint's token on the browser API.
        assertEquals(401, post(token(OWNER, API_AUDIENCE, "knowledge:read", "claude"), initialize(), null).statusCode());
        assertEquals(403, post(token(OWNER, ENDPOINT, "profile", "claude"), initialize(), null).statusCode());
        var api = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/identity/me"))
                .header("Authorization", "Bearer " + endpointToken()).timeout(Duration.ofSeconds(5)).build();
        assertEquals(401, HTTP.send(api, HttpResponse.BodyHandlers.ofString()).statusCode());

        assertEquals(200, post(endpointToken(), initialize(), null).statusCode());
    }

    @Test
    void theEndpointPublishesOnlyItsTwoReadOnlyTools() throws Exception {
        var response = post(endpointToken(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "2025-11-25");
        assertEquals(200, response.statusCode());
        var tools = json(response.body()).path("result").path("tools");
        assertEquals(Set.of("search", "fetch"), Set.copyOf(StreamSupport.stream(tools.spliterator(), false)
                .map(tool -> tool.path("name").asText()).toList()));
        for (JsonNode tool : tools) {
            var hints = tool.path("annotations");
            assertTrue(hints.path("readOnlyHint").asBoolean(), tool.toString());
            assertFalse(hints.path("destructiveHint").asBoolean(true), tool.toString());
            assertTrue(hints.path("idempotentHint").asBoolean(), tool.toString());
            assertFalse(hints.path("openWorldHint").asBoolean(true), tool.toString());
            assertFalse(tool.path("title").asText().isBlank(), tool.toString());
            assertEquals("object", tool.path("outputSchema").path("type").asText(), tool.toString());
        }
    }

    @Test
    void searchReturnsCitableEvidenceWithAMemoryOsLinkForAnUpload() throws Exception {
        UUID document = UUID.randomUUID();
        when(documents.search(any(), any())).thenReturn(new SearchPage(List.of(new SearchPage.Result(document,
                UUID.randomUUID(), "Leave policy", "text/plain", Instant.parse("2026-09-30T00:00:00Z"), 1.0,
                List.of(new SearchPage.Section(0, 0, 0, 1.0, "Annual leave is 12 days.", List.of()))))
                , 0, false, 1, 50, new SearchPage.SourceFacets(1, List.of())));

        var result = json(post(endpointToken(), call("search", Map.of("query", "leave")), "2025-11-25").body())
                .path("result");
        var first = result.path("structuredContent").path("results").get(0);
        assertEquals(document.toString(), first.path("id").asText());
        assertEquals(1, first.path("sourceNumber").asInt());
        assertEquals("Annual leave is 12 days.", first.path("text").asText());
        assertEquals("http://localhost/search?doc=" + document, first.path("url").asText());
        assertFalse(result.path("content").isEmpty(), "the same result also travels as text");
    }

    @Test
    void aFailureReachesTheClientWithoutItsCause() throws Exception {
        when(documents.search(any(), any())).thenThrow(new IllegalStateException("opensearch at 10.0.0.5 refused"));
        String body = post(endpointToken(), call("search", Map.of("query", "leave")), "2025-11-25").body();
        assertFalse(body.contains("10.0.0.5"), body);
        assertFalse(body.contains("IllegalStateException"), body);
        assertTrue(body.contains(McpEndpointTools.ToolFailure.FAILED), body);
    }

    @Test
    void chatGptsModernProbeIsAnsweredSoItFallsBackToInitialize() throws Exception {
        var response = post(endpointToken(), "{\"jsonrpc\":\"2.0\",\"id\":\"d\",\"method\":\"server/discover\"}", "2026-07-28");
        assertEquals(400, response.statusCode());
        assertEquals(-32000, json(response.body()).path("error").path("code").asInt());
    }

    @Test
    void aForeignBrowserOriginIsRefused() throws Exception {
        var request = request(endpointToken(), initialize(), null).header("Origin", "https://evil.example").build();
        assertEquals(403, HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void anOversizedBodyIsRefused() throws Exception {
        String padding = "x".repeat(300_000);
        var response = post(endpointToken(), call("search", Map.of("query", padding)), "2025-11-25");
        assertEquals(413, response.statusCode());
    }

    @Test
    void toolCallsAreLimitedPerCallerAndListingIsFree() throws Exception {
        when(documents.search(any(), any())).thenReturn(new SearchPage(List.of(), 0, false, 0, 50,
                new SearchPage.SourceFacets(0, List.of())));
        String caller = token(OWNER, ENDPOINT, "knowledge:read", "client-" + UUID.randomUUID());
        for (int i = 0; i < CALLER_CALLS_PER_MINUTE; i++) {
            post(caller, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}", "2025-11-25");
            assertEquals(200, post(caller, call("search", Map.of("query", "leave")), "2025-11-25").statusCode());
        }
        var limited = post(caller, call("search", Map.of("query", "leave")), "2025-11-25");
        assertEquals(429, limited.statusCode());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        // The same person through another client has a bucket of their own.
        assertEquals(200, post(endpointToken(), call("search", Map.of("query", "leave")), "2025-11-25").statusCode());
    }

    @Test
    void anAdministratorTurnsTheEndpointOnAndOffAndEachChangeIsAudited() throws Exception {
        jdbc.sql("DELETE FROM mcp_endpoint_setting").update();
        String owner = token(OWNER, API_AUDIENCE, "openid", "memoryos-web");
        var settings = json(api(owner, "GET", null).body());
        assertTrue(settings.path("configured").asBoolean());
        assertFalse(settings.path("enabled").asBoolean());
        assertEquals(ENDPOINT, settings.path("url").asText());
        assertEquals(0, settings.path("revision").asLong());

        assertEquals(409, api(owner, "PUT", "{\"enabled\":true,\"revision\":7}").statusCode());
        var on = json(api(owner, "PUT", "{\"enabled\":true,\"revision\":0}").body());
        assertTrue(on.path("enabled").asBoolean());
        assertEquals(200, post(endpointToken(), initialize(), null).statusCode());

        api(owner, "PUT", "{\"enabled\":false,\"revision\":" + on.path("revision").asLong() + "}");
        assertEquals(404, post(endpointToken(), initialize(), null).statusCode());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'mcp_endpoint.change'")
                .query(Long.class).single());
        // A member token for the endpoint cannot manage it.
        var api = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp/endpoint"))
                .header("Authorization", "Bearer " + endpointToken()).timeout(Duration.ofSeconds(5)).build();
        assertEquals(401, HTTP.send(api, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private HttpResponse<String> api(String bearer, String method, @Nullable String body)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp/endpoint"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + bearer);
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").header(BrowserMutation.HEADER, BrowserMutation.VALUE)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void endpoint(boolean enabled) {
        jdbc.sql("""
                INSERT INTO mcp_endpoint_setting(tenant_id, enabled, revision) VALUES (:tenant, :enabled, 1)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled
                """).param("tenant", UUID.fromString(TENANT)).param("enabled", enabled).update();
    }

    private static String initialize() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                 "capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""";
    }

    private static String call(String tool, Map<String, String> arguments) throws IOException {
        return Json.mapper().writeValueAsString(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)));
    }

    private static String endpointToken() {
        return token(OWNER, ENDPOINT, "knowledge:read offline_access", "client-" + UUID.randomUUID());
    }

    private HttpResponse<String> post(@Nullable String bearer, String body, @Nullable String protocolVersion)
            throws IOException, InterruptedException {
        return HTTP.send(request(bearer, body, protocolVersion).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(@Nullable String bearer, String body, @Nullable String protocolVersion) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (protocolVersion != null) builder.header("MCP-Protocol-Version", protocolVersion);
        return builder;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(String body) throws IOException {
        return Json.mapper().readTree(body);
    }

    private static String token(String subject, String audience, String scope, String client) {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(audience)
                .claim("scope", scope).claim("azp", client)
                .issueTime(Date.from(Instant.now())).notBeforeTime(Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(SIGNING_KEY));
            return jwt.serialize();
        } catch (JOSEException failure) {
            throw new IllegalStateException("Could not sign test JWT", failure);
        }
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("memoryos-mcp-test-key").generate();
        } catch (JOSEException failure) {
            throw new IllegalStateException("Could not generate test RSA key", failure);
        }
    }

    private static HttpServer startJwkServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/jwks", exchange -> send(exchange, new JWKSet(SIGNING_KEY.toPublicJWK()).toString()));
            server.createContext("/.well-known/openid-configuration", exchange -> {
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
                send(exchange, """
                        {"issuer":"%1$s","authorization_endpoint":"%1$s/authorize","token_endpoint":"%1$s/token",
                         "jwks_uri":"%1$s/jwks","userinfo_endpoint":"%1$s/userinfo","subject_types_supported":["public"],
                         "id_token_signing_alg_values_supported":["RS256"]}""".formatted(issuer));
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not start test JWK server", failure);
        }
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
