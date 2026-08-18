package io.memoryos.api.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtAuthenticationIntegrationTest {

    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "memoryos-api";
    private static final String BOUND_SUBJECT = "bound-subject";
    private static final String ACTOR_ID = "00000000-0000-0000-0000-000000000001";
    private static final RSAKey SIGNING_KEY = rsaKey();
    private static final RSAKey WRONG_KEY = rsaKey();
    private static final HttpServer JWK_SERVER = startJwkServer();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
        registry.add("memoryos.identity.audience", () -> AUDIENCE);
        registry.add("memoryos.identity.bindings[0].issuer", () -> ISSUER);
        registry.add("memoryos.identity.bindings[0].subject", () -> BOUND_SUBJECT);
        registry.add("memoryos.identity.bindings[0].actor-id", () -> ACTOR_ID);
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
    void returnsOnlyActorIdForBoundIdentity() throws Exception {
        var response = request(token(validClaims(BOUND_SUBJECT), SIGNING_KEY));

        assertEquals(200, response.statusCode());
        assertEquals("{\"actorId\":\"" + ACTOR_ID + "\"}", response.body());
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
            server.createContext("/jwks", exchange -> {
                var body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start test JWK server", exception);
        }
    }
}
