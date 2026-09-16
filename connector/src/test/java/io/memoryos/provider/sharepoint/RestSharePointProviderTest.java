package io.memoryos.provider.sharepoint;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointProviderException.Failure;
import io.memoryos.connector.SharePointProviderException.Reason;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestSharePointProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsTheRootSiteWithABearerTokenAndTheMicrosoftUserAgent() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("/v1.0/sites/root", exchange.getRequestURI().getPath());
            assertTrue(exchange.getRequestURI().getQuery().contains("siteCollection"));
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").startsWith("ISV|MemoryOS|"));
            return ok("""
                    {"id":"contoso.sharepoint.com,1,2","webUrl":"https://contoso.sharepoint.com",
                     "siteCollection":{"hostname":"contoso.sharepoint.com"}}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var root = session.root();
            assertEquals("contoso.sharepoint.com,1,2", root.siteId());
            assertEquals("https://contoso.sharepoint.com", root.webUrl());
            assertEquals("contoso.sharepoint.com", root.hostname());
        }
    }

    @Test
    void classifiesGraphStatusCodesWithoutEchoingMicrosoftText() throws Exception {
        Map<Integer, Failure> expected = Map.of(401, Failure.AUTHENTICATION, 403, Failure.AUTHORIZATION,
                404, Failure.NOT_FOUND, 410, Failure.NOT_FOUND, 429, Failure.QUOTA,
                500, Failure.UNAVAILABLE, 503, Failure.UNAVAILABLE, 408, Failure.UNAVAILABLE, 400, Failure.MALFORMED);
        for (var entry : expected.entrySet()) {
            try (var fixture = new Fixture(_ -> new Response(entry.getKey(),
                    "{\"error\":{\"code\":\"secret\",\"message\":\"internal detail\"}}".getBytes(StandardCharsets.UTF_8)));
                    var provider = provider(fixture, 0); var session = provider.open(credential())) {
                var exception = assertThrows(SharePointProviderException.class, session::root);
                assertEquals(entry.getValue(), exception.failure(), "status " + entry.getKey());
                assertFalse(exception.getMessage().contains("internal detail"));
            }
        }
    }

    @Test
    void neverFollowsRedirects() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/elsewhere")) return ok("{\"id\":\"leaked\"}");
            exchange.getResponseHeaders().add("Location", "/v1.0/elsewhere");
            return new Response(302, new byte[0]);
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure());
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test
    void rejectsOversizedAndUnusableBodies() throws Exception {
        try (var fixture = new Fixture(_ -> ok("{\"id\":\"" + "x".repeat(4096) + "\"}"));
                var provider = provider(fixture, 1024); var session = provider.open(credential())) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(SharePointProviderException.class, session::root).failure());
        }
        try (var fixture = new Fixture(_ -> ok("not json"));
                var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure());
        }
        try (var fixture = new Fixture(_ -> ok("{\"id\":\"site\",\"webUrl\":\"https://contoso.sharepoint.com\"}"));
                var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure(),
                    "a missing siteCollection.hostname is not a usable root site");
        }
    }

    @Test
    void refusesCloudsOtherThanTheConfiguredOneAndClosedSessions() throws Exception {
        try (var fixture = new Fixture(_ -> ok("{\"id\":\"site\"}")); var provider = provider(fixture, 0)) {
            var session = provider.open(credential());
            session.close();
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure());
            assertEquals(0, fixture.requests.size());
        }
    }

    @Test
    void classifiesEntraErrorNumbers() {
        assertEquals(Reason.INVALID_CLIENT_SECRET, MsalSharePointTokenSource.classify(
                "AADSTS7000215: Invalid client secret provided. Ensure the secret being sent in the request is the client secret value"));
        assertEquals(Reason.EXPIRED_CLIENT_SECRET, MsalSharePointTokenSource.classify("AADSTS7000222: The provided client secret keys are expired."));
        assertEquals(Reason.CERTIFICATE_NOT_REGISTERED, MsalSharePointTokenSource.classify(
                "AADSTS700027: The certificate with identifier used to sign the client assertion is not registered on application."));
        assertEquals(Reason.DIRECTORY_NOT_FOUND, MsalSharePointTokenSource.classify(
                "AADSTS900021: Requested tenant identifier is not valid."));
        assertEquals(Reason.APPLICATION_NOT_FOUND, MsalSharePointTokenSource.classify(
                "AADSTS700016: Application with identifier was not found in the directory."));
        assertEquals(Reason.CONSENT_REQUIRED, MsalSharePointTokenSource.classify("AADSTS65001: The user or administrator has not consented"));
        assertEquals(Reason.UNCLASSIFIED, MsalSharePointTokenSource.classify("AADSTS123456: Something else"));
        assertEquals(Reason.UNCLASSIFIED, MsalSharePointTokenSource.classify("connection reset"));
        assertEquals(Reason.UNCLASSIFIED, MsalSharePointTokenSource.classify(null));
    }

    private RestSharePointProvider provider(Fixture fixture, int maxResponseBytes) {
        var properties = new SharePointProviderProperties(fixture.base, URI.create(fixture.base + "/v1.0"),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10), 0, maxResponseBytes, null);
        return new RestSharePointProvider(properties, mapper, _ -> "test-token");
    }

    private static SharePointProvider.Credential credential() {
        return SharePointProvider.Credential.clientSecret(SharePointProvider.Cloud.GLOBAL,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "secret".getBytes(StandardCharsets.UTF_8));
    }

    private static Response ok(String body) {
        return new Response(200, body.getBytes(StandardCharsets.UTF_8));
    }

    private record Response(int status, byte[] body) {}

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final URI base;
        final List<URI> requests = Collections.synchronizedList(new ArrayList<>());

        Fixture(Function<HttpExchange, Response> responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI());
                Response response = responder.apply(exchange);
                exchange.sendResponseHeaders(response.status(), response.body().length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response.body());
                }
                exchange.close();
            });
            server.start();
        }

        @Override public void close() { server.stop(0); }
    }
}
