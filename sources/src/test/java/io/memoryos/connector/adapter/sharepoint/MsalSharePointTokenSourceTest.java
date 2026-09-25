package io.memoryos.connector.adapter.sharepoint;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.memoryos.connector.sharepoint.SharePointCertificate;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointProviderException.Failure;
import io.memoryos.connector.SharePointProviderException.Reason;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the real msal4j client-credentials exchange. Entra authorities must use https, so the token
 * endpoint is served over TLS with a checked-in localhost certificate the test JVM trusts.
 */
class MsalSharePointTokenSourceTest {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIRECTORY = UUID.randomUUID().toString();
    private static final String CLIENT = UUID.randomUUID().toString();

    @AfterAll
    static void shutdown() { EXECUTOR.close(); }

    @Test
    void sendsTheClientSecretToTheDirectoryTokenEndpoint() throws Exception {
        try (var fixture = new Fixture(_ -> token("secret-token"))) {
            String token = source(fixture).token(SharePointProvider.Credential.clientSecret(
                    SharePointProvider.Cloud.GLOBAL, DIRECTORY, CLIENT, "the-secret".getBytes(StandardCharsets.UTF_8)));
            assertEquals("secret-token", token);
            assertTrue(fixture.path.contains(DIRECTORY), fixture.path);
            assertEquals(CLIENT, fixture.form.get("client_id"));
            assertEquals("the-secret", fixture.form.get("client_secret"));
            assertEquals("client_credentials", fixture.form.get("grant_type"));
            // msal4j always appends the OIDC scopes to the resource scope.
            assertTrue(Objects.requireNonNull(fixture.form.get("scope")).startsWith("https://graph.microsoft.com/.default"),
                    fixture.form.get("scope"));
        }
    }

    @Test
    void signsAClientAssertionWithTheCertificateThumbprint() throws Exception {
        try (var material = certificate(); var fixture = new Fixture(_ -> token("certificate-token"))) {
            String token = source(fixture).token(SharePointProvider.Credential.certificate(
                    SharePointProvider.Cloud.GLOBAL, DIRECTORY, CLIENT, material.privateKey(), material.certificate()));
            assertEquals("certificate-token", token);
            assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", fixture.form.get("client_assertion_type"));
            assertNull(fixture.form.get("client_secret"));
            String[] parts = Objects.requireNonNull(fixture.form.get("client_assertion")).split("\\.");
            assertEquals(3, parts.length);
            var header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            var payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            assertEquals("RS256", header.path("alg").asString(""));
            // The assertion identifies the certificate by thumbprint, which is what an administrator uploaded to Entra.
            String sha1 = header.path("x5t").asString("");
            String sha256 = header.path("x5t#S256").asString("");
            assertFalse(sha1.isBlank() && sha256.isBlank(), header.toString());
            if (!sha1.isBlank()) {
                assertEquals(material.thumbprint(), java.util.HexFormat.of().withUpperCase().formatHex(decode(sha1)));
            }
            if (!sha256.isBlank()) {
                assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(material.certificate()),
                        decode(sha256));
            }
            assertEquals(CLIENT, payload.path("iss").asString(""));
            assertEquals(CLIENT, payload.path("sub").asString(""));
            assertTrue(payload.path("aud").asString("").contains(DIRECTORY), payload.path("aud").asString(""));
        }
    }

    @Test
    void classifiesRejectionsAsAuthenticationFailures() throws Exception {
        try (var fixture = new Fixture(_ -> new Response(401, """
                {"error":"invalid_client","error_description":"AADSTS7000215: Invalid client secret provided."}"""))) {
            var exception = assertThrows(SharePointProviderException.class, () -> source(fixture).token(
                    SharePointProvider.Credential.clientSecret(SharePointProvider.Cloud.GLOBAL, DIRECTORY, CLIENT,
                            "wrong".getBytes(StandardCharsets.UTF_8))));
            assertEquals(Failure.AUTHENTICATION, exception.failure());
            assertEquals(Reason.INVALID_CLIENT_SECRET, exception.reason());
        }
    }

    @Test
    void reportsUnclassifiedDirectoryFailures() throws Exception {
        try (var fixture = new Fixture(_ -> new Response(503, "{\"error\":\"temporarily_unavailable\"}"))) {
            var exception = assertThrows(SharePointProviderException.class, () -> source(fixture).token(
                    SharePointProvider.Credential.clientSecret(SharePointProvider.Cloud.GLOBAL, DIRECTORY, CLIENT,
                            "the-secret".getBytes(StandardCharsets.UTF_8))));
            assertEquals(Reason.UNCLASSIFIED, exception.reason());
        }
    }

    private static MsalSharePointTokenSource source(Fixture fixture) {
        return new MsalSharePointTokenSource(new SharePointProviderProperties(fixture.base,
                URI.create(fixture.base + "/v1.0"), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(20), 0, 0, 0, 0, null), EXECUTOR);
    }

    /** msal4j writes the thumbprint in standard base64; the JWT specification allows base64url. */
    private static byte[] decode(String value) {
        return Base64.getMimeDecoder().decode(value.replace('-', '+').replace('_', '/'));
    }

    private static SharePointCertificate certificate() throws IOException {
        return SharePointCertificate.read(fixture("rsa2048.pfx"), PASSWORD.clone(), Instant.now());
    }

    private static Response token(String accessToken) {
        return new Response(200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"ext_expires_in\":3599,"
                + "\"access_token\":\"" + accessToken + "\"}");
    }

    private static byte[] fixture(String name) throws IOException {
        try (var stream = MsalSharePointTokenSourceTest.class.getResourceAsStream("/sharepoint/" + name + ".base64")) {
            return Base64.getDecoder().decode(
                    new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8).strip());
        }
    }

    private record Response(int status, String body) {}

    private static final class Fixture implements AutoCloseable {
        private static final SSLContext CONTEXT = localhostTls();

        final HttpsServer server;
        final URI base;
        volatile String path = "";
        final Map<String, String> form = new HashMap<>();

        Fixture(Function<HttpExchange, Response> responder) throws IOException {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(CONTEXT));
            base = URI.create("https://localhost:" + server.getAddress().getPort());
            server.createContext("/", exchange -> {
                path = exchange.getRequestURI().getPath();
                form.clear();
                for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                    int equals = pair.indexOf('=');
                    if (equals > 0) {
                        form.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                                URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                    }
                }
                Response response = responder.apply(exchange);
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
                exchange.close();
            });
            server.start();
        }

        @Override public void close() { server.stop(0); }

        /** msal4j uses the JVM default SSL context, so the localhost certificate is installed once as that default. */
        private static SSLContext localhostTls() {
            try {
                var store = KeyStore.getInstance("PKCS12");
                store.load(new java.io.ByteArrayInputStream(fixture("localhost.p12")), PASSWORD.clone());
                var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(store, PASSWORD.clone());
                var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(store);
                var context = SSLContext.getInstance("TLS");
                context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
                SSLContext.setDefault(context);
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
                return context;
            } catch (Exception exception) {
                throw new IllegalStateException("could not start the local token endpoint over TLS", exception);
            }
        }
    }
}
