package io.memoryos.integration.google;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.GoogleDriveAccountClient.Consent;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.shared.Sha256;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
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

    @Test
    void anExchangeOnADeploymentThatIsNotConfiguredSaysSoInsteadOfAnInvalidAnswer() {
        var unconfigured = client(null, URI.create(issuer + "/token"));
        try (var client = new GoogleDriveOAuthClient(CLIENT_ID, "test-client-secret".getBytes(StandardCharsets.UTF_8))) {
            assertEquals("GOOGLE_DRIVE_NOT_CONFIGURED", assertThrows(GoogleDriveException.class,
                    () -> unconfigured.exchange("code", "verifier", "nonce", client)).code());
        }
    }

    @Test
    void theKeySetAndTheTokenAnswerAreReadWithinABound() {
        AtomicInteger keySets = new AtomicInteger();
        byte[] token = ("{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"token_type\":\"Bearer\","
                + "\"id_token\":\"e30.e30.c2ln\"}").getBytes(StandardCharsets.UTF_8);
        server.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(200, token.length);
            try (var output = exchange.getResponseBody()) { output.write(token); }
        });
        // A key set that never ends: sent without a declared length, far past the bound.
        server.createContext("/jwks", exchange -> {
            keySets.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            byte[] block = "{\"keys\":[".concat(" ".repeat(8_192)).getBytes(StandardCharsets.UTF_8);
            try (var output = exchange.getResponseBody()) {
                for (int sent = 0; sent < 4_194_304; sent += block.length) output.write(block);
            } catch (IOException closed) {
                // The client stopped reading at its bound.
            }
        });
        try (var client = new GoogleDriveOAuthClient(CLIENT_ID, "test-client-secret".getBytes(StandardCharsets.UTF_8))) {
            var failure = assertThrows(IllegalStateException.class, () -> accounts.exchange("code", "verifier", "nonce", client));
            assertNull(failure.getCause());
            assertEquals(1, keySets.get());
        }
        byte[] large = new byte[131_072];
        server.removeContext("/token");
        server.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(200, large.length);
            try (var output = exchange.getResponseBody()) { output.write(large); } catch (IOException closed) { /* refused */ }
        });
        try (var client = new GoogleDriveOAuthClient(CLIENT_ID, "test-client-secret".getBytes(StandardCharsets.UTF_8))) {
            assertThrows(IllegalStateException.class, () -> accounts.exchange("code", "verifier", "nonce", client));
            assertEquals(1, keySets.get(), "an oversized token answer ends the consent before the key set is fetched");
        }
    }

    /** Every rule of the identity token, each broken alone on a token signed by the key set the consent reads. */
    @Test
    void acceptsOnlyAnIdentityTokenThatMeetsEveryRule() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("drive-key").generate();
        byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", exchange -> {
            exchange.sendResponseHeaders(200, keys.length);
            try (var output = exchange.getResponseBody()) { output.write(keys); }
        });
        byte[] about = "{\"user\":{\"emailAddress\":\"owner@example.com\"}}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/drive/v3/about", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, about.length);
            try (var output = exchange.getResponseBody()) { output.write(about); }
        });
        var answer = new AtomicReference<byte[]>();
        server.createContext("/token", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.get().length);
            try (var output = exchange.getResponseBody()) { output.write(answer.get()); }
        });
        String access = "test-access";
        Instant now = Instant.now();
        Function<UnaryOperator<JWTClaimsSet.Builder>, Boolean> accepted = change -> {
            var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("google-subject").audience(CLIENT_ID)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600))).claim("nonce", "flow-nonce")
                    .claim("email", "owner@example.com").claim("email_verified", true)
                    .claim("at_hash", accessTokenHash(access));
            try {
                var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("drive-key").build(),
                        change.apply(claims).build());
                token.sign(new RSASSASigner(key));
                answer.set(("{\"access_token\":\"" + access + "\",\"refresh_token\":\"test-refresh\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":3600,\"id_token\":\"" + token.serialize() + "\"}").getBytes(StandardCharsets.UTF_8));
            } catch (JOSEException failure) {
                throw new IllegalStateException(failure);
            }
            try (var client = new GoogleDriveOAuthClient(CLIENT_ID, "test-client-secret".getBytes(StandardCharsets.UTF_8))) {
                var grant = accounts.exchange("code", "verifier", "flow-nonce", client);
                assertEquals("google-subject", grant.accountSubject());
                return true;
            } catch (IllegalStateException refused) {
                assertNull(refused.getCause());
                return false;
            }
        };

        assertTrue(accepted.apply(claims -> claims));
        assertTrue(accepted.apply(claims -> claims.claim("at_hash", null)), "at_hash is optional in the code flow");
        assertTrue(accepted.apply(claims -> claims.audience(List.of(CLIENT_ID, "other")).claim("azp", CLIENT_ID)));
        var refused = Map.<String, UnaryOperator<JWTClaimsSet.Builder>>of(
                "issuer", claims -> claims.issuer("https://accounts.example.test"),
                "audience", claims -> claims.audience("another-client"),
                "authorized party", claims -> claims.claim("azp", "another-client"),
                "several audiences without an authorized party", claims -> claims.audience(List.of(CLIENT_ID, "other")),
                "nonce", claims -> claims.claim("nonce", "another-flow"),
                "unverified email", claims -> claims.claim("email_verified", false),
                "no email", claims -> claims.claim("email", null),
                "access token hash", claims -> claims.claim("at_hash", accessTokenHash("another-access")),
                "issued in the future", claims -> claims.issueTime(Date.from(now.plusSeconds(600))),
                "expired", claims -> claims.expirationTime(Date.from(now.minusSeconds(600))));
        refused.forEach((rule, change) -> assertFalse(accepted.apply(change), rule));
    }

    private RestGoogleDriveAccountClient client(URI callback, URI tokenUri) {
        return new RestGoogleDriveAccountClient(new GoogleDriveProviderProperties(tokenUri, URI.create(issuer + "/drive/v3"),
                null, null, null, null, null, null, 0, 0, 0, 0, 0, 0,
                callback, URI.create(issuer + "/authorize"), null, URI.create(issuer + "/jwks"), URI.create(issuer)),
                new ObjectMapper());
    }

    private static String accessTokenHash(String token) {
        byte[] hash = Sha256.digest(token.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, hash.length / 2));
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
