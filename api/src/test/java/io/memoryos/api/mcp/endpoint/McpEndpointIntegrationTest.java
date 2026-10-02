package io.memoryos.api.mcp.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import io.memoryos.chat.DocumentSetService;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.McpClientGrant;
import io.memoryos.iam.McpClientGrantException;
import io.memoryos.iam.McpClientGrantFailureReason;
import io.memoryos.iam.McpClientGrants;
import io.memoryos.iam.McpClientPolicy;
import io.memoryos.mcp.McpEndpointCallRetention;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.core.util.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.mockito.ArgumentCaptor;
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
    @MockitoBean private DocumentSetService documentSets;
    /** Keycloak's consents are the iam adapter's contract; here only what the API passes and returns is checked. */
    @MockitoBean private McpClientGrants grants;
    /** Keycloak's policy is the iam adapter's contract; here only the hosts the API asks it to trust are checked. */
    @MockitoBean private McpClientPolicy policy;
    @Autowired private McpEndpointCallRetention retention;
    @Autowired private MeterRegistry meters;

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
        when(policy.configured()).thenReturn(true);
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
    void theEndpointPublishesOnlyItsThreeReadOnlyTools() throws Exception {
        var response = post(endpointToken(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "2025-11-25");
        assertEquals(200, response.statusCode());
        var tools = json(response.body()).path("result").path("tools");
        assertEquals(Set.of("search", "fetch", "search_with_filters"), Set.copyOf(StreamSupport.stream(tools.spliterator(), false)
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
    void theInstructionsAskForClaimsLinkedToTheirSource() throws Exception {
        // A bare [n] reads as plain text in Claude and ChatGPT; a Markdown link to the result's url opens the source.
        String instructions = json(post(endpointToken(), initialize(), null).body()).path("result").path("instructions")
                .asText();
        assertTrue(instructions.contains("Markdown link"), instructions);
        assertFalse(instructions.contains("[1]"), instructions);
        var tools = json(post(endpointToken(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "2025-11-25")
                .body()).path("result").path("tools");
        for (JsonNode tool : tools) {
            String description = tool.path("description").asText();
            assertTrue(description.contains("Markdown link"), description);
            assertFalse(description.contains("[n]"), description);
        }
    }

    @Test
    void searchWithFiltersOffersEachFilterWithItsAllowedValues() throws Exception {
        var tools = json(post(endpointToken(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "2025-11-25")
                .body()).path("result").path("tools");
        var schema = StreamSupport.stream(tools.spliterator(), false)
                .filter(tool -> tool.path("name").asText().equals("search_with_filters")).findFirst().orElseThrow()
                .path("inputSchema");
        assertEquals(Set.of("query", "source_types", "document_set_names", "updated_after", "updated_before", "file_types"),
                Set.copyOf(names(schema.path("properties"))), schema.toString());
        assertEquals(List.of("query"), values(schema.path("required")), schema.toString());
        assertEquals(Set.of("FILE", "GOOGLE_DRIVE", "SHAREPOINT"),
                Set.copyOf(values(schema.path("properties").path("source_types").path("items").path("enum"))), schema.toString());
        assertEquals(Set.of("PDF", "WORD", "POWERPOINT", "SPREADSHEET", "TEXT", "MARKDOWN"),
                Set.copyOf(values(schema.path("properties").path("file_types").path("items").path("enum"))), schema.toString());
    }

    @Test
    void searchWithFiltersNarrowsTheSearchToWhatThePersonAsked() throws Exception {
        UUID contracts = UUID.randomUUID();
        when(documentSets.list(any(), eq(0), eq(100))).thenReturn(List.of(set(contracts, "Hợp đồng"), set(UUID.randomUUID(), "Nhân sự")));
        when(documents.search(any(), any())).thenReturn(new SearchPage(List.of(), 0, false, 0, 50, new SearchPage.SourceFacets(0, List.of())));

        var result = json(post(endpointToken(), call("search_with_filters", Map.of("query", "doanh thu",
                "source_types", List.of("SHAREPOINT"), "document_set_names", List.of(" hợp đồng "),
                "updated_after", "2026-07-01", "updated_before", "2026-07-31", "file_types", List.of("PDF", "MARKDOWN"))),
                "2025-11-25").body()).path("result");
        assertFalse(result.path("isError").asBoolean(), result.toString());
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(documents).search(any(), request.capture());
        assertEquals(List.of(SourceType.SHAREPOINT), request.getValue().sourceTypes());
        assertEquals(List.of(contracts), request.getValue().documentSetIds());
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), request.getValue().updatedFrom());
        assertEquals(Instant.parse("2026-07-31T23:59:59.999999999Z"), request.getValue().updatedTo());
        assertEquals(List.of("application/pdf", "text/markdown", "text/x-markdown"), request.getValue().mediaTypes());
    }

    @Test
    void anUnknownDocumentSetIsAnsweredWithTheSetsThePersonCanUse() throws Exception {
        when(documentSets.list(any(), eq(0), eq(100))).thenReturn(List.of(set(UUID.randomUUID(), "Hợp đồng"), set(UUID.randomUUID(), "Nhân sự")));
        String body = post(endpointToken(), call("search_with_filters", Map.of("query", "lương",
                "document_set_names", List.of("Tài chính"))), "2025-11-25").body();
        assertEquals("Document set \"Tài chính\" not found. Available: Hợp đồng, Nhân sự.", failureText(body), body);
        verify(documents, never()).search(any(), any());
    }

    @Test
    void aPeriodTheToolCannotReadIsRefusedWithTheFormatToUse() throws Exception {
        String malformed = post(endpointToken(), call("search_with_filters", Map.of("query", "lương",
                "updated_after", "01/07/2026")), "2025-11-25").body();
        assertEquals("Pass updated_after as a date such as 2026-03-01.", failureText(malformed), malformed);
        String reversed = post(endpointToken(), call("search_with_filters", Map.of("query", "lương",
                "updated_after", "2026-08-01", "updated_before", "2026-07-01")), "2025-11-25").body();
        assertEquals(McpEndpointTools.INVALID_WINDOW, failureText(reversed), reversed);
        verify(documents, never()).search(any(), any());
    }

    @Test
    void anUnknownSourceIsRefusedWithoutReachingSearch() throws Exception {
        String body = post(endpointToken(), call("search_with_filters", Map.of("query", "lương",
                "source_types", List.of("DROPBOX"))), "2025-11-25").body();
        // The MCP SDK checks arguments against the input schema, naming the field and the values it accepts.
        String text = failureText(body);
        assertTrue(text.contains("/source_types/0") && text.contains("GOOGLE_DRIVE"), text);
        assertFalse(text.contains("io.memoryos"), text);
        verify(documents, never()).search(any(), any());
    }

    private static DocumentSetService.View set(UUID id, String name) {
        return new DocumentSetService.View(id, new DocumentSetService.Permissions(false, false, false, false), 1, name, "",
                false, List.of(), List.of(), 0, List.of(), List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    private static List<String> names(JsonNode object) {
        var names = new ArrayList<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
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
        assertTrue(first.path("sources").isArray(), first.toString());
        assertFalse(result.path("content").isEmpty(), "the same result also travels as text");
    }

    @Test
    void aFailureReachesTheClientWithoutItsCause() throws Exception {
        when(documents.search(any(), any())).thenThrow(new IllegalStateException("opensearch at 10.0.0.5 refused"));
        String body = post(endpointToken(), call("search", Map.of("query", "leave")), "2025-11-25").body();
        assertFalse(body.contains("10.0.0.5"), body);
        assertFalse(body.contains("IllegalStateException"), body);
        assertEquals(McpEndpointTools.ToolFailure.FAILED, failureText(body), body);
    }

    @Test
    void aClientThatAcceptsOnlyJsonIsServedRatherThanRefused() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Accept", "application/json").header("Authorization", "Bearer " + endpointToken())
                .POST(HttpRequest.BodyPublishers.ofString(initialize())).build();
        assertEquals(200, HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aRefusedCallTellsTheClientWhatToChange() throws Exception {
        String body = post(endpointToken(), call("fetch", Map.of("id", "not-an-id")), "2025-11-25").body();
        assertEquals(McpEndpointTools.INVALID_ID, failureText(body), body);
    }

    /** The one text of a failed call, which must be a single sentence and flagged as an error. */
    private static String failureText(String body) throws IOException {
        JsonNode result = json(body).path("result");
        assertTrue(result.path("isError").asBoolean(), body);
        assertEquals(1, result.path("content").size(), body);
        return result.path("content").get(0).path("text").asText();
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
        // A refused call is in the activity log too, with the tool it asked for.
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM mcp_endpoint_calls WHERE outcome = 'RATE_LIMITED' AND tool = 'search'")
                .query(Long.class).single());
        // The counter names the bucket that ran dry and the app, never the client ID or the tool the caller named.
        assertEquals(1.0, meters.get("memoryos.mcp.endpoint.rate_limited").tags("scope", "caller", "client", "other")
                .counter().count());
        // The same person through another client has a bucket of their own.
        assertEquals(200, post(endpointToken(), call("search", Map.of("query", "leave")), "2025-11-25").statusCode());
    }

    @Test
    void anAdministratorTurnsTheEndpointOnAndOffAndEachChangeIsAudited() throws Exception {
        jdbc.sql("DELETE FROM mcp_endpoint_setting").update();
        String owner = token(OWNER, API_AUDIENCE, "openid", "memoryos-web");
        var settings = json(api(owner, "GET", "/api/mcp/endpoint", null).body());
        assertTrue(settings.path("configured").asBoolean());
        assertFalse(settings.path("enabled").asBoolean());
        assertEquals(ENDPOINT, settings.path("url").asText());
        assertEquals(0, settings.path("revision").asLong());

        assertEquals(409, api(owner, "PUT", "/api/mcp/endpoint", "{\"enabled\":true,\"revision\":7}").statusCode());
        var on = json(api(owner, "PUT", "/api/mcp/endpoint", "{\"enabled\":true,\"revision\":0}").body());
        assertTrue(on.path("enabled").asBoolean());
        assertEquals(200, post(endpointToken(), initialize(), null).statusCode());

        api(owner, "PUT", "/api/mcp/endpoint", "{\"enabled\":false,\"revision\":" + on.path("revision").asLong() + "}");
        assertEquals(404, post(endpointToken(), initialize(), null).statusCode());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'mcp_endpoint.change'")
                .query(Long.class).single());
        // A member token for the endpoint cannot manage it.
        var api = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp/endpoint"))
                .header("Authorization", "Bearer " + endpointToken()).timeout(Duration.ofSeconds(5)).build();
        assertEquals(401, HTTP.send(api, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aMemberIsGivenTheUrlAndTheTrustedAppsWhileTheEndpointAnswers() throws Exception {
        jdbc.sql("DELETE FROM mcp_trusted_apps").update();
        String owner = token(OWNER, API_AUDIENCE, "openid", "memoryos-web");
        // ChatGPT connects through its metadata document; nobody is handed a client secret any more.
        assertFalse(json(api(owner, "GET", "/api/mcp/endpoint", null).body()).has("chatGpt"));

        var connection = json(api(owner, "GET", "/api/mcp/endpoint/connection", null).body());
        assertTrue(connection.path("available").asBoolean());
        assertEquals(ENDPOINT, connection.path("url").asText());
        assertEquals(List.of("CLAUDE", "CHATGPT"), StreamSupport.stream(connection.path("apps").spliterator(), false)
                .map(JsonNode::asText).toList());

        endpoint(false);
        var off = json(api(owner, "GET", "/api/mcp/endpoint/connection", null).body());
        assertFalse(off.path("available").asBoolean());
        assertTrue(off.path("url").isNull(), off.toString());
    }

    @Test
    void anAdministratorTrustsAnAppOfTheirOwnAndKeycloakFollowsEachChange() throws Exception {
        jdbc.sql("DELETE FROM mcp_trusted_apps").update();
        String owner = token(OWNER, API_AUDIENCE, "openid", "memoryos-web");
        var listed = json(api(owner, "GET", "/api/mcp/endpoint/trusted-apps", null).body());
        assertTrue(listed.path("manageable").asBoolean(), listed.toString());
        assertEquals(List.of("CLAUDE", "CHATGPT"), StreamSupport.stream(listed.path("apps").spliterator(), false)
                .map(app -> app.path("preset").asText()).toList());
        var chatGpt = listed.path("apps").get(1);
        assertTrue(chatGpt.path("builtIn").asBoolean());
        assertEquals("[\"chatgpt.com\",\"persistent.oaistatic.com\"]", chatGpt.path("documentHosts").toString());

        var added = api(owner, "POST", "/api/mcp/endpoint/trusted-apps",
                "{\"name\":\" Agent \",\"clientIdHosts\":[\"Agent.Example.com\"],\"documentHosts\":[\"localhost\"]}");
        assertEquals(201, added.statusCode(), added.body());
        var agent = json(added.body());
        assertEquals("Agent", agent.path("name").asText());
        assertEquals("[\"agent.example.com\",\"localhost\"]", agent.path("documentHosts").toString());
        var trusted = ArgumentCaptor.forClass(McpClientPolicy.Hosts.class);
        verify(policy, atLeastOnce()).trust(trusted.capture());
        assertEquals(Set.of("claude.ai", "claude.com", "chatgpt.com", "agent.example.com"),
                trusted.getValue().clientIdHosts());
        verify(policy, atLeastOnce()).removeClientsOutside(trusted.getValue());

        // ChatGPT switched off: its host leaves the policy and its clients go with it.
        var off = api(owner, "PUT", "/api/mcp/endpoint/trusted-apps/" + chatGpt.path("id").asText(),
                "{\"enabled\":false,\"revision\":" + chatGpt.path("revision").asLong() + "}");
        assertEquals(200, off.statusCode(), off.body());
        verify(policy, atLeastOnce()).trust(trusted.capture());
        assertEquals(Set.of("claude.ai", "claude.com", "agent.example.com"), trusted.getValue().clientIdHosts());
        assertEquals(List.of("CLAUDE", "CUSTOM"), StreamSupport.stream(json(api(owner, "GET",
                "/api/mcp/endpoint/connection", null).body()).path("apps").spliterator(), false).map(JsonNode::asText).toList());

        // A stale revision, a built-in app and an invalid domain are refused, and nothing reaches Keycloak.
        clearInvocations(policy);
        assertEquals(409, api(owner, "PUT", "/api/mcp/endpoint/trusted-apps/" + chatGpt.path("id").asText(),
                "{\"enabled\":true,\"revision\":" + chatGpt.path("revision").asLong() + "}").statusCode());
        assertEquals(400, api(owner, "DELETE", "/api/mcp/endpoint/trusted-apps/" + chatGpt.path("id").asText()
                + "?revision=2", "").statusCode());
        assertEquals(400, api(owner, "POST", "/api/mcp/endpoint/trusted-apps",
                "{\"name\":\"Bad\",\"clientIdHosts\":[\"https://agent.example.com\"],\"documentHosts\":[]}")
                .statusCode());
        verify(policy, never()).trust(any());

        assertEquals(204, api(owner, "DELETE", "/api/mcp/endpoint/trusted-apps/" + agent.path("id").asText()
                + "?revision=" + agent.path("revision").asLong(), "").statusCode());
        verify(policy).trust(trusted.capture());
        assertEquals(Set.of("claude.ai", "claude.com"), trusted.getValue().clientIdHosts());
        assertEquals(3L, jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'mcp_trusted_app.change'")
                .query(Long.class).single());

        // Without the Keycloak account the list is read-only.
        when(policy.configured()).thenReturn(false);
        assertFalse(json(api(owner, "GET", "/api/mcp/endpoint/trusted-apps", null).body()).path("manageable").asBoolean());
        assertEquals(503, api(owner, "POST", "/api/mcp/endpoint/trusted-apps",
                "{\"name\":\"Agent\",\"clientIdHosts\":[\"agent.example.com\"],\"documentHosts\":[]}").statusCode());
    }

    @Test
    void everyToolCallReachesTheActivityLogWithoutItsQueryAndTheInsightsCountIt() throws Exception {
        jdbc.sql("DELETE FROM mcp_endpoint_calls").update();
        when(documents.search(any(), any())).thenReturn(new SearchPage(List.of(), 0, false, 0, 50,
                new SearchPage.SourceFacets(0, List.of())));
        String claude = token(OWNER, ENDPOINT, "knowledge:read", "https://claude.ai/oauth/mcp-oauth-client-metadata");
        assertEquals(200, post(claude, call("search", Map.of("query", "secret salary review")), "2025-11-25").statusCode());
        post(endpointToken(), call("fetch", Map.of("id", "not-an-id")), "2025-11-25");

        String owner = token(OWNER, API_AUDIENCE, "openid", "memoryos-web");
        var page = json(api(owner, "GET", "/api/mcp/endpoint/activity", null).body());
        var calls = page.path("calls");
        assertEquals(2, calls.size(), page.toString());
        assertEquals("fetch", calls.get(0).path("tool").asText());
        assertEquals("REFUSED", calls.get(0).path("outcome").asText());
        assertEquals("OTHER", calls.get(0).path("client").asText());
        assertEquals("search", calls.get(1).path("tool").asText());
        assertEquals("SUCCESS", calls.get(1).path("outcome").asText());
        assertEquals("CLAUDE", calls.get(1).path("client").asText());
        assertEquals("Claude", calls.get(1).path("clientName").asText());
        assertFalse(page.toString().contains("salary"), "The log keeps no query");
        assertTrue(page.path("next").isNull(), page.toString());

        var claudeOnly = json(api(owner, "GET", "/api/mcp/endpoint/activity?client=CLAUDE&size=1", null).body());
        assertEquals(1, claudeOnly.path("calls").size(), claudeOnly.toString());

        var insights = json(api(owner, "GET", "/api/mcp/endpoint/insights?days=7", null).body());
        assertEquals(2, insights.path("calls").asLong(), insights.toString());
        assertEquals(1, insights.path("people").asLong(), insights.toString());
        assertEquals(2, insights.path("apps").size(), insights.toString());
        assertEquals(1, insights.path("daily").size(), insights.toString());
        assertEquals(400, api(owner, "GET", "/api/mcp/endpoint/insights?days=14", null).statusCode());

        // Ninety days on, the Worker's retention removes the call; younger ones stay.
        jdbc.sql("UPDATE mcp_endpoint_calls SET occurred_at = now() - interval '91 days' WHERE tool = 'search'").update();
        assertEquals(1, retention.purge());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM mcp_endpoint_calls").query(Long.class).single());
    }

    @Test
    void aMemberListsTheirGrantsAndRevokesOneByItsUrlClientId() throws Exception {
        String claude = "https://claude.ai/oauth/mcp-oauth-client-metadata";
        when(grants.list(any())).thenReturn(List.of(new McpClientGrant(claude, McpClientGrant.Client.CLAUDE, "Claude",
                Instant.parse("2026-10-01T09:30:00Z"))));
        String owner = token(OWNER, API_AUDIENCE, "openid", "memoryos-web");

        var listed = json(api(owner, "GET", "/api/mcp/grants", null).body());
        assertEquals(1, listed.size(), listed.toString());
        assertEquals(claude, listed.get(0).path("clientId").asText());
        assertEquals("CLAUDE", listed.get(0).path("client").asText());
        assertEquals("2026-10-01T09:30:00Z", listed.get(0).path("grantedAt").asText());

        String query = "/api/mcp/grants?clientId=" + URLEncoder.encode(claude, StandardCharsets.UTF_8);
        assertEquals(204, api(owner, "DELETE", query, "").statusCode());
        verify(grants).revoke(any(), eq(claude));

        doThrow(new McpClientGrantException(McpClientGrantFailureReason.NOT_FOUND, "no such grant"))
                .when(grants).revoke(any(), eq("memoryos-web"));
        var unknown = api(owner, "DELETE", "/api/mcp/grants?clientId=memoryos-web", "");
        assertEquals(404, unknown.statusCode());
        assertEquals("MCP_CLIENT_GRANT_NOT_FOUND", json(unknown.body()).path("code").asText(), unknown.body());
    }

    private HttpResponse<String> api(String bearer, String method, String path, @Nullable String body)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
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

    private static String call(String tool, Map<String, ?> arguments) throws IOException {
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
