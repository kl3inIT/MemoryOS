package io.memoryos.connector.adapter.googledrive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.GoogleDriveAccountClient.Consent;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The Google account consent protocol; the callback and its browser session are covered by the API's GoogleDriveOAuthTest. */
class RestGoogleDriveAccountClientTest {
    private static final String CALLBACK = "http://127.0.0.1:8080/login/oauth2/code/google-drive";
    private static final String CLIENT_ID = "drive-client.apps.googleusercontent.com";
    private HttpServer server;
    private String issuer;
    private RestGoogleDriveAccountClient accounts;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
        accounts = client(URI.create(CALLBACK), URI.create(issuer + "/token"));
    }

    @AfterEach
    void teardown() { server.stop(0); }

    @Test
    void consentUrlCarriesPkceStateNonceAndScopesButNeverTheSecret() {
        Map<String, String> parameters = query(URI.create(accounts.authorizationUrl(CLIENT_ID,
                new Consent("flow-state", "flow-nonce", "flow-challenge"))).getRawQuery());
        assertEquals(CLIENT_ID, parameters.get("client_id"));
        assertEquals(CALLBACK, parameters.get("redirect_uri"));
        assertEquals("flow-state", parameters.get("state"));
        assertEquals("flow-nonce", parameters.get("nonce"));
        assertEquals("flow-challenge", parameters.get("code_challenge"));
        assertEquals("S256", parameters.get("code_challenge_method"));
        assertEquals("offline", parameters.get("access_type"));
        assertTrue(parameters.get("scope").contains("https://www.googleapis.com/auth/documents.readonly"));
        assertFalse(parameters.containsKey("client_secret"));
    }

    @Test
    void missingOrUnsafeSettingsAreNotConfiguredOnlyWhenUsed() {
        for (URI callback : new URI[]{null, URI.create(""), URI.create("http://app.example.test/login/oauth2/code/google-drive"),
                URI.create("https://app.example.test/login/oauth2/code/other"),
                URI.create("https://app.example.test/login/oauth2/code/google-drive?next=x")}) {
            var unconfigured = client(callback, URI.create(issuer + "/token"));
            assertEquals("GOOGLE_DRIVE_NOT_CONFIGURED",
                    assertThrows(GoogleDriveException.class, unconfigured::requireConfigured).code());
            assertEquals("GOOGLE_DRIVE_NOT_CONFIGURED",
                    assertThrows(GoogleDriveException.class, () -> unconfigured.parseClient(clientJson())).code());
        }
        var plainToken = client(URI.create(CALLBACK), URI.create("http://tokens.example.test/token"));
        assertEquals("GOOGLE_DRIVE_NOT_CONFIGURED",
                assertThrows(GoogleDriveException.class, plainToken::requireConfigured).code());
    }

    @Test
    void acceptsOnlyBoundedWebClientJsonWithExactConfiguredCallback() {
        String json = clientJson();
        try (var parsed = accounts.parseClient(json + " ".repeat(16384 - json.length()))) {
            assertEquals(CLIENT_ID, parsed.clientId());
            assertArrayEquals("test-client-secret".getBytes(StandardCharsets.UTF_8), parsed.clientSecret());
            assertFalse(parsed.toString().contains("test-client-secret"));
        }
        assertNull(accounts.parseClient(null));
        for (String invalid : new String[]{
                "", "{", json + "{}", json.replace("\"web\"", "\"installed\""),
                json.replace("/login/oauth2/code/google-drive", "/login/oauth2/code/google-drive/"),
                json.replace("\"client_secret\":", "\"client_secret\":\"duplicate\",\"client_secret\":"),
                json.replace("test-client-secret", "x".repeat(4097)),
                json.replace("\"client_id\":", "\"project_id\":\"" + "界".repeat(6000) + "\",\"client_id\":"),
                json.replace("\"client_id\":", "\"project_id\":\"" + "x".repeat(16384) + "\",\"client_id\":")}) {
            var failure = assertThrows(GoogleDriveException.class, () -> accounts.parseClient(invalid));
            assertEquals("GOOGLE_DRIVE_OAUTH_CLIENT_INVALID", failure.code());
            assertFalse(failure.toString().contains("test-client-secret"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void uploadedEndpointsCannotChooseTokenAuthorizationOrJwksHosts() {
        for (String field : new String[]{"token_uri", "auth_uri", "auth_provider_x509_cert_url"}) {
            String json = clientJson().replace("\"client_id\":", "\"" + field + "\":\"" + issuer + "/stolen\",\"client_id\":");
            assertEquals("GOOGLE_DRIVE_OAUTH_CLIENT_INVALID",
                    assertThrows(GoogleDriveException.class, () -> accounts.parseClient(json)).code());
        }
    }

    @Test
    void tokenEndpointRedirectDoesNotForwardClientSecret() {
        AtomicInteger stolen = new AtomicInteger();
        server.createContext("/token", exchange -> {
            exchange.getResponseHeaders().set("Location", issuer + "/stolen");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/stolen", exchange -> { stolen.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        try (var client = new GoogleDriveOAuthClient(CLIENT_ID, "test-client-secret".getBytes(StandardCharsets.UTF_8))) {
            var failure = assertThrows(RuntimeException.class, () -> accounts.exchange("code", "verifier", "nonce", client));
            assertFalse(failure.toString().contains("test-client-secret"));
            assertNull(failure.getCause());
            assertEquals(0, stolen.get());
        }
    }

    private RestGoogleDriveAccountClient client(URI callback, URI tokenUri) {
        return new RestGoogleDriveAccountClient(new GoogleDriveProviderProperties(tokenUri, URI.create(issuer + "/drive/v3"),
                null, null, null, null, null, null, 0, 0, 0, 0, 0, 0,
                callback, URI.create(issuer + "/authorize"), null, URI.create(issuer + "/jwks"), URI.create(issuer)),
                new ObjectMapper());
    }

    private static String clientJson() {
        return "{\"web\":{\"client_id\":\"" + CLIENT_ID + "\",\"client_secret\":\"test-client-secret\","
                + "\"redirect_uris\":[\"" + CALLBACK + "\"]}}";
    }

    private static Map<String, String> query(String query) {
        var result = new HashMap<String, String>();
        for (String entry : query.split("&")) {
            String[] pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
