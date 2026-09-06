package io.memoryos.api.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.api.ApiPostgresDatabase;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection", "SqlWithoutWhere"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BearerAuthenticationIntegrationTest {

    private static final String AUDIENCE = "memoryos-api";
    private static final String BOUND_SUBJECT = "bound-subject";
    private static final String ACTOR_ID = "00000000-0000-0000-0000-000000000001";
    private static final RSAKey SIGNING_KEY = rsaKey();
    private static final RSAKey WRONG_KEY = rsaKey();
    private static final HttpServer JWK_SERVER = startJwkServer();
    private static final String ISSUER = issuer(JWK_SERVER);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        ApiPostgresDatabase.configure(registry);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
        registry.add("memoryos.identity.audience", () -> AUDIENCE);
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
        registry.add("memoryos.identity.keycloak.admin.client-secret", () -> "test-provisioner-secret");
        registry.add(
                "memoryos.identity.keycloak.admin.action-redirect-uri",
                () -> "http://127.0.0.1/invite/activate"
        );
        registry.add("spring.security.oauth2.client.registration.memoryos.client-secret", () -> "client-secret");
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.client.provider.memoryos.authorization-uri",
                () -> ISSUER + "/authorize");
        registry.add("spring.security.oauth2.client.provider.memoryos.token-uri", () -> ISSUER + "/token");
        registry.add("spring.security.oauth2.client.provider.memoryos.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
        registry.add("spring.security.oauth2.client.provider.memoryos.user-info-uri",
                () -> ISSUER + "/userinfo");
        registry.add("spring.security.oauth2.client.provider.memoryos.user-name-attribute", () -> "sub");
        registry.add(
                "arconia.multitenancy.resolution.fixed.tenant-identifier",
                () -> "10000000-0000-0000-0000-000000000024"
        );
        registry.add("memoryos.initial-tenant.id", () -> "10000000-0000-0000-0000-000000000024");
        registry.add("memoryos.initial-tenant.owner-subject", () -> "startup-owner");
        registry.add("memoryos.initial-tenant.slug", () -> "test");
        registry.add("memoryos.initial-tenant.display-name", () -> "Test");
        registry.add("memoryos.initial-tenant.change-reference", () -> "TEST-JWT-BOOTSTRAP");
    }

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void seedBoundIdentity() {
        jdbcClient.sql("DELETE FROM external_identity_bindings WHERE actor_id = :actorId")
                .param("actorId", UUID.fromString(ACTOR_ID))
                .update();
        jdbcClient.sql("DELETE FROM actors WHERE id = :actorId")
                .param("actorId", UUID.fromString(ACTOR_ID))
                .update();
        jdbcClient
                .sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", UUID.fromString(ACTOR_ID))
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO external_identity_bindings (issuer, subject, actor_id)
                        VALUES (:issuer, :subject, :actorId)
                        """)
                .param("issuer", ISSUER)
                .param("subject", BOUND_SUBJECT)
                .param("actorId", UUID.fromString(ACTOR_ID))
                .update();
    }

    @AfterAll
    static void stopJwkServer() {
        JWK_SERVER.stop(0);
    }

    @Test
    void rejectsMissingBearerToken() throws Exception {
        assertEquals(401, request(null).statusCode());
    }

    @Test
    void rejectsMalformedBearerToken() throws Exception {
        assertEquals(401, request("not-a-jwt").statusCode());
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        assertEquals(401, request(token(validClaims(BOUND_SUBJECT), WRONG_KEY)).statusCode());
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        var claims = claims(
                "https://other-issuer.example.test",
                BOUND_SUBJECT,
                List.of(AUDIENCE),
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300));
        assertEquals(401, request(token(claims, SIGNING_KEY)).statusCode());
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        var claims = claims(
                ISSUER,
                BOUND_SUBJECT,
                List.of("other-api"),
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300));
        assertEquals(401, request(token(claims, SIGNING_KEY)).statusCode());
    }

    @Test
    void rejectsExpiredTokenBeyondClockSkew() throws Exception {
        var claims = claims(
                ISSUER,
                BOUND_SUBJECT,
                List.of(AUDIENCE),
                Instant.now().minusSeconds(300),
                Instant.now().minusSeconds(120));
        assertEquals(401, request(token(claims, SIGNING_KEY)).statusCode());
    }

    @Test
    void rejectsTokenBeforeNotBeforeTimeBeyondClockSkew() throws Exception {
        var claims = claims(
                ISSUER,
                BOUND_SUBJECT,
                List.of(AUDIENCE),
                Instant.now().plusSeconds(120),
                Instant.now().plusSeconds(300));
        assertEquals(401, request(token(claims, SIGNING_KEY)).statusCode());
    }

    @Test
    void rejectsMissingSubject() throws Exception {
        assertEquals(401, request(token(validClaims(null), SIGNING_KEY)).statusCode());
    }

    @Test
    void rejectsBlankSubject() throws Exception {
        assertEquals(401, request(token(validClaims(" "), SIGNING_KEY)).statusCode());
    }

    @Test
    void rejectsValidTokenWithoutIdentityBinding() throws Exception {
        assertEquals(401, request(token(validClaims("unbound-subject"), SIGNING_KEY)).statusCode());
    }

    @Test
    void rejectsMatchingEmailWithoutExplicitIdentityBinding() throws Exception {
        var claims = new JWTClaimsSet.Builder(validClaims("unbound-subject"))
                .claim("email", "bound-user@example.test")
                .build();

        assertEquals(401, request(token(claims, SIGNING_KEY)).statusCode());
    }

    @Test
    void returnsEmptyTenantAuthorityForBoundActorWithoutMembership() throws Exception {
        var response = request(token(validClaims(BOUND_SUBJECT), SIGNING_KEY));

        assertEquals(200, response.statusCode());
        assertEquals(
                "{\"actorId\":\"" + ACTOR_ID
                        + "\",\"tenant\":null,\"capabilities\":[],\"scopedCapabilities\":[],\"authorizationVersion\":0}",
                response.body()
        );
    }

    @Test
    void returnsDurableTenantAuthorityForBoundOwner() throws Exception {
        UUID ownerActorId = jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'startup-owner'
                        """)
                .param("issuer", ISSUER)
                .query(UUID.class)
                .single();

        var response = request(token(validClaims("startup-owner"), SIGNING_KEY));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(ownerActorId.toString()));
        assertTrue(response.body().contains("\"role\":\"OWNER\""));
        assertTrue(response.body().contains("\"USERS_MANAGE\""));
        assertTrue(response.body().contains("\"SOURCES_MANAGE\""));
    }

    @Test
    void ignoresTenantHeadersAndUsesTheFixedDeploymentTenant() throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/identity/me"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token(validClaims("startup-owner"), SIGNING_KEY))
                .header("X-TenantId", "20000000-0000-0000-0000-000000000024")
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                "Test",
                io.swagger.v3.core.util.Json.mapper()
                        .readTree(response.body())
                        .path("tenant")
                        .path("displayName")
                        .textValue()
        );
    }

    @Test
    void rejectsOversizedDeclaredUploadForAnAuthorizedOwner() throws Exception {
        String body = """
                {
                  "filename": "oversized.txt",
                  "mediaType": "text/plain",
                  "sizeBytes": 10485761,
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
                """;
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port
                                + "/api/sources/" + UUID.randomUUID() + "/uploads"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token(validClaims("startup-owner"), SIGNING_KEY))
                .header(BrowserMutation.HEADER, BrowserMutation.VALUE)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertEquals(
                "REQUEST_VALIDATION",
                io.swagger.v3.core.util.Json.mapper().readTree(response.body()).path("code").textValue()
        );
    }


    private HttpResponse<String> request(String token) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/identity/me"))
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JWTClaimsSet validClaims(String subject) {
        return claims(
                ISSUER,
                subject,
                List.of(AUDIENCE),
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300));
    }

    private static JWTClaimsSet claims(
            String issuer,
            String subject,
            List<String> audience,
            Instant notBefore,
            Instant expiresAt) {
        var builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(Instant.now()))
                .notBeforeTime(Date.from(notBefore))
                .expirationTime(Date.from(expiresAt));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }

    private static String token(JWTClaimsSet claims, RSAKey key) {
        try {
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign test JWT", exception);
        }
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("memoryos-test-key").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not generate test RSA key", exception);
        }
    }

    private static HttpServer startJwkServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/jwks", exchange -> sendJson(
                    exchange,
                    new JWKSet(SIGNING_KEY.toPublicJWK()).toString()
            ));
            server.createContext("/.well-known/openid-configuration", exchange -> {
                String issuer = issuer(server);
                sendJson(exchange, """
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/authorize",
                          "token_endpoint": "%s/token",
                          "jwks_uri": "%s/jwks",
                          "userinfo_endpoint": "%s/userinfo",
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """.formatted(issuer, issuer, issuer, issuer, issuer));
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start test JWK server", exception);
        }
    }

    private static String issuer(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

}
