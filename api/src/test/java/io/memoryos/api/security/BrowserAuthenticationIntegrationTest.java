package io.memoryos.api.security;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.api.invitation.InvitationSessionState;
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

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection",
        "HttpHeaderInspection",
        "SqlWithoutWhere"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrowserAuthenticationIntegrationTest {

    private static final RSAKey SIGNING_KEY = rsaKey();
    private static final Map<String, AuthorizationGrant> AUTHORIZATION_GRANTS = new ConcurrentHashMap<>();
    private static final AtomicReference<String> AUTHENTICATING_SUBJECT = new AtomicReference<>("initial-owner");
    private static final AtomicReference<String> AUTHENTICATING_EMAIL =
            new AtomicReference<>("owner@example.com");
    private static final AtomicReference<Boolean> AUTHENTICATING_EMAIL_VERIFIED =
            new AtomicReference<>(true);
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String ISSUER = "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();
    private static final String CLIENT_ID = "memoryos-web";
    private static final String PROVIDER_ID_TOKEN_MARKER = "provider-id-token-marker";
    private static final String PROVIDER_ACCESS_TOKEN = "provider-access-token";
    private static final String BROWSER_MUTATION_HEADER = "X-MemoryOS-CSRF";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("memoryos.identity.audience", () -> "memoryos-api");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:browser-auth;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.security.oauth2.client.registration.memoryos.client-id", () -> CLIENT_ID);
        registry.add("spring.security.oauth2.client.registration.memoryos.client-secret", () -> "client-secret");
        registry.add("spring.security.oauth2.client.registration.memoryos.client-authentication-method",
                () -> "client_secret_basic");
        registry.add("spring.security.oauth2.client.registration.memoryos.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.memoryos.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.registration.memoryos.scope", () -> "openid");
        registry.add("spring.security.oauth2.client.registration.memoryos.provider", () -> "memoryos");
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> ISSUER);
        registry.add("memoryos.initial-organization.owner-subject", () -> "initial-owner");
        registry.add("memoryos.initial-organization.slug", () -> "tasco");
        registry.add("memoryos.initial-organization.display-name", () -> "Tasco");
        registry.add("memoryos.initial-organization.default-workspace-slug", () -> "default");
        registry.add("memoryos.initial-organization.default-workspace-display-name", () -> "Tasco Default Workspace");
        registry.add("memoryos.initial-organization.change-reference", () -> "TEST-BROWSER-BOOTSTRAP");
    }

    @AfterAll
    static void stopIdentityServer() {
        IDENTITY_SERVER.stop(0);
    }

    @Test
    void rejectsAnonymousIdentityWithoutCreatingASession() throws Exception {
        long sessionCount = count("spring_session");
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        try (var client = client(cookies)) {
            var response = client.send(request("/api/identity/me"), HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
            assertTrue(response.headers().allValues("set-cookie").isEmpty());
            assertTrue(cookies.getCookieStore().getCookies().isEmpty());
            assertEquals(sessionCount, count("spring_session"));
        }
    }

    @Test
    void authenticatesTheInitialOwnerWithPkceAndPersistsOnlyTheActorSession() throws Exception {
        AUTHENTICATING_SUBJECT.set("initial-owner");
        UUID ownerActorId = jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'initial-owner'
                        """)
                .param("issuer", ISSUER)
                .query(UUID.class)
                .single();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        try (var client = client(cookies)) {
            var authorization = client.send(
                    request("/oauth2/authorization/memoryos"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(302, authorization.statusCode());
            String preAuthenticationSessionId = sessionCookie(cookies);
            URI providerAuthorization = URI.create(authorization.headers().firstValue("location").orElseThrow());
            Map<String, String> authorizationQuery = query(providerAuthorization);
            assertEquals("S256", authorizationQuery.get("code_challenge_method"));
            assertNotNull(authorizationQuery.get("code_challenge"));

            var providerResponse = client.send(
                    HttpRequest.newBuilder(providerAuthorization).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(302, providerResponse.statusCode());
            var callbackResponse = client.send(
                    HttpRequest.newBuilder(URI.create(providerResponse.headers().firstValue("location").orElseThrow()))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(302, callbackResponse.statusCode());
            assertEquals(baseUri().resolve("/").toString(), callbackResponse.headers().firstValue("location").orElseThrow());
            assertNotEquals(preAuthenticationSessionId, sessionCookie(cookies));

            var authenticated = client.send(request("/"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, authenticated.statusCode());
            assertTrue(authenticated.body().contains(ownerActorId.toString()));
            var currentIdentity = client.send(
                    request("/api/identity/me"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, currentIdentity.statusCode());
            assertTrue(currentIdentity.body().contains(ownerActorId.toString()));
            assertEquals(1L, count("organizations"));
            assertEquals(1L, count("workspaces"));
            assertEquals(1L, count("organization_memberships"));
            assertEquals(1L, count("workspace_memberships"));

            List<byte[]> attributes = jdbcClient.sql("SELECT attribute_bytes FROM spring_session_attributes")
                    .query(byte[].class)
                    .list();
            assertFalse(attributes.isEmpty());
            for (byte[] attribute : attributes) {
                String serialized = new String(attribute, ISO_8859_1);
                assertFalse(serialized.contains(PROVIDER_ID_TOKEN_MARKER));
                assertFalse(serialized.contains(PROVIDER_ACCESS_TOKEN));
            }
        }
    }

    @Test
    void returnsProblemDetailsForBusinessAndFrameworkFailures() throws Exception {
        AUTHENTICATING_SUBJECT.set("initial-owner");
        AUTHENTICATING_EMAIL.set("owner@example.test");
        AUTHENTICATING_EMAIL_VERIFIED.set(true);
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        try (var client = client(cookies)) {
            assertEquals(
                    baseUri().resolve("/").toString(),
                    completeOAuth(client, "/oauth2/authorization/memoryos")
                            .headers().firstValue("location").orElseThrow()
            );

            var businessFailure = client.send(
                    invitationMutation("{\"email\":\"not-an-email\"}"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(400, businessFailure.statusCode());
            assertEquals(
                    "application/problem+json",
                    businessFailure.headers().firstValue("content-type").orElseThrow()
            );
            assertEquals(
                    "urn:memoryos:failure:invitation-invalid-email",
                    jsonString(businessFailure.body(), "type")
            );
            assertEquals("Validation failed", jsonString(businessFailure.body(), "title"));
            assertEquals("Enter a valid email address.", jsonString(businessFailure.body(), "detail"));
            assertEquals("/api/invitations", jsonString(businessFailure.body(), "instance"));
            assertEquals("INVITATION_INVALID_EMAIL", jsonString(businessFailure.body(), "code"));
            assertTrue(businessFailure.body().contains("\"status\":400"));

            var frameworkFailure = client.send(
                    invitationMutation("{"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(400, frameworkFailure.statusCode());
            assertEquals(
                    "application/problem+json",
                    frameworkFailure.headers().firstValue("content-type").orElseThrow()
            );
            assertFalse(frameworkFailure.body().contains("\"type\""));
            assertEquals("Bad Request", jsonString(frameworkFailure.body(), "title"));
            assertEquals("Failed to read request", jsonString(frameworkFailure.body(), "detail"));
            assertEquals("/api/invitations", jsonString(frameworkFailure.body(), "instance"));
            assertTrue(frameworkFailure.body().contains("\"status\":400"));
            assertFalse(frameworkFailure.body().contains("\"code\""));

            var sameOriginFailure = client.send(
                    HttpRequest.newBuilder(baseUri().resolve("/api/invitations"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"member@example.test\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(403, sameOriginFailure.statusCode());
            assertEquals(
                    "application/problem+json",
                    sameOriginFailure.headers().firstValue("content-type").orElseThrow()
            );
            assertEquals("Forbidden", jsonString(sameOriginFailure.body(), "title"));
            assertEquals(
                    "same-origin browser request required",
                    jsonString(sameOriginFailure.body(), "detail")
            );
            assertEquals("/api/invitations", jsonString(sameOriginFailure.body(), "instance"));
            assertTrue(sameOriginFailure.body().contains("\"status\":403"));
            assertFalse(sameOriginFailure.body().contains("\"code\""));
        } finally {
            jdbcClient.sql("DELETE FROM spring_session").update();
        }
    }

    @Test
    void derivesTheOAuthCallbackFromTheForwardedHttpsOrigin() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var request = HttpRequest.newBuilder(baseUri().resolve("/oauth2/authorization/memoryos"))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "memoryos.example.test")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try (var client = client(cookies)) {
            var authorization = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(302, authorization.statusCode());
            URI providerAuthorization = URI.create(authorization.headers().firstValue("location").orElseThrow());
            assertEquals(
                    "https://memoryos.example.test/login/oauth2/code/memoryos",
                    query(providerAuthorization).get("redirect_uri")
            );
        }
    }

    @Test
    void rejectsABoundIdentityWithoutOrganizationMembershipAndInvalidatesItsSession() throws Exception {
        AUTHENTICATING_SUBJECT.set("unprovisioned-user");
        UUID unprovisionedActorId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", unprovisionedActorId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO external_identity_bindings (issuer, subject, actor_id)
                        VALUES (:issuer, 'unprovisioned-user', :actorId)
                        """)
                .param("issuer", ISSUER)
                .param("actorId", unprovisionedActorId)
                .update();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        try (var client = client(cookies)) {
            var authorization = client.send(
                    request("/oauth2/authorization/memoryos"),
                    HttpResponse.BodyHandlers.ofString()
            );
            URI providerAuthorization = URI.create(authorization.headers().firstValue("location").orElseThrow());
            var providerResponse = client.send(
                    HttpRequest.newBuilder(providerAuthorization).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            var callbackResponse = client.send(
                    HttpRequest.newBuilder(URI.create(providerResponse.headers().firstValue("location").orElseThrow()))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(302, callbackResponse.statusCode());
            assertEquals(
                    baseUri().resolve("/access-not-provisioned").toString(),
                    callbackResponse.headers().firstValue("location").orElseThrow()
            );

            var failure = client.send(request("/access-not-provisioned"), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, failure.statusCode());
            assertTrue(failure.body().contains("ACCESS_NOT_PROVISIONED"));
            assertEquals(1L, jdbcClient.sql("""
                            SELECT COUNT(*) FROM external_identity_bindings
                            WHERE issuer = :issuer
                              AND subject = 'unprovisioned-user'
                              AND actor_id = :actorId
                            """)
                    .param("issuer", ISSUER)
                    .param("actorId", unprovisionedActorId)
                    .query(Long.class)
                    .single());
            var root = client.send(request("/"), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, root.statusCode());
            assertTrue(root.headers().firstValue("location").orElseThrow()
                    .endsWith("/oauth2/authorization/memoryos"));
        }
    }

    @Test
    void acceptsInvitationThroughPkceAndPersistsOnlyTheMemberActorSession() throws Exception {
        String memberSubject = "invited-member-" + UUID.randomUUID();
        String memberEmail = memberSubject + "@example.test";
        String invitationUrl;
        var ownerCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        try (var ownerClient = client(ownerCookies)) {
            AUTHENTICATING_SUBJECT.set("initial-owner");
            AUTHENTICATING_EMAIL.set("owner@example.test");
            AUTHENTICATING_EMAIL_VERIFIED.set(true);
            assertEquals(
                    baseUri().resolve("/").toString(),
                    completeOAuth(ownerClient, "/oauth2/authorization/memoryos")
                            .headers().firstValue("location").orElseThrow()
            );

            var create = ownerClient.send(
                    invitationMutation("""
                            {"email":"%s"}
                            """.formatted(memberEmail)),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(201, create.statusCode());
            invitationUrl = jsonString(create.body(), "invitationUrl");
            assertTrue(invitationUrl.startsWith("/invite/"));
            assertFalse(databaseContains(invitationUrl.substring("/invite/".length())));
        }

        var memberCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var memberClient = client(memberCookies)) {
            var intake = memberClient.send(request(invitationUrl), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, intake.statusCode());
            assertEquals("/invitation", intake.headers().firstValue("location").orElseThrow());
            assertEquals("no-store", intake.headers().firstValue("cache-control").orElseThrow());
            assertEquals("no-referrer", intake.headers().firstValue("referrer-policy").orElseThrow());

            var current = memberClient.send(
                    request("/api/invitations/current"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, current.statusCode());
            assertEquals("no-store", current.headers().firstValue("cache-control").orElseThrow());

            var invalidIntake = memberClient.send(
                    request("/invite/not-a-valid-secret"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(303, invalidIntake.statusCode());
            assertEquals(
                    "/invitation?reason=not-available",
                    invalidIntake.headers().firstValue("location").orElseThrow()
            );
            assertEquals(
                    410,
                    memberClient.send(
                            request("/api/invitations/current"),
                            HttpResponse.BodyHandlers.ofString()
                    ).statusCode()
            );

            assertEquals(
                    303,
                    memberClient.send(request(invitationUrl), HttpResponse.BodyHandlers.ofString()).statusCode()
            );
            current = memberClient.send(
                    request("/api/invitations/current"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, current.statusCode());
            assertEquals("Tasco", jsonString(current.body(), "organizationDisplayName"));

            AUTHENTICATING_SUBJECT.set(memberSubject);
            AUTHENTICATING_EMAIL.set(memberEmail);
            AUTHENTICATING_EMAIL_VERIFIED.set(true);
            var continueResponse = memberClient.send(
                    request(jsonString(current.body(), "continueUrl")),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(303, continueResponse.statusCode());
            var callback = completeOAuth(
                    memberClient,
                    continueResponse.headers().firstValue("location").orElseThrow()
            );
            assertEquals(302, callback.statusCode());
            assertEquals(baseUri().resolve("/").toString(), callback.headers().firstValue("location").orElseThrow());

            var identity = memberClient.send(request("/api/identity/me"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, identity.statusCode());
            UUID memberActorId = jdbcClient.sql("""
                            SELECT actor_id FROM external_identity_bindings
                            WHERE issuer = :issuer AND subject = :subject
                            """)
                    .param("issuer", ISSUER)
                    .param("subject", memberSubject)
                    .query(UUID.class)
                    .single();
            assertTrue(identity.body().contains(memberActorId.toString()));
            assertEquals("MEMBER", membershipRole("organization_memberships", memberActorId));
            assertEquals("MEMBER", membershipRole("workspace_memberships", memberActorId));
            assertEquals("ACCEPTED", jdbcClient.sql("""
                            SELECT status FROM organization_invitations
                            WHERE normalized_email = :email
                            """)
                    .param("email", memberEmail)
                    .query(String.class)
                    .single());

            assertPersistedSessionsContainNoProviderOrInvitationState();
        } finally {
            AUTHENTICATING_SUBJECT.set("initial-owner");
            AUTHENTICATING_EMAIL.set("owner@example.test");
            AUTHENTICATING_EMAIL_VERIFIED.set(true);
            deleteInvitedMember(memberSubject, memberEmail);
            jdbcClient.sql("DELETE FROM spring_session").update();
        }
    }

    @Test
    void rejectsMismatchedInvitationEmailAndInvalidatesThePartialSession() throws Exception {
        String memberSubject = "mismatched-member-" + UUID.randomUUID();
        String invitedEmail = memberSubject + "@example.test";
        String invitationUrl;
        var ownerCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        try (var ownerClient = client(ownerCookies)) {
            AUTHENTICATING_SUBJECT.set("initial-owner");
            AUTHENTICATING_EMAIL.set("owner@example.test");
            AUTHENTICATING_EMAIL_VERIFIED.set(true);
            assertEquals(
                    baseUri().resolve("/").toString(),
                    completeOAuth(ownerClient, "/oauth2/authorization/memoryos")
                            .headers().firstValue("location").orElseThrow()
            );
            var create = ownerClient.send(
                    invitationMutation("""
                            {"email":"%s"}
                            """.formatted(invitedEmail)),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(201, create.statusCode());
            invitationUrl = jsonString(create.body(), "invitationUrl");
        }

        var memberCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var memberClient = client(memberCookies)) {
            assertEquals(
                    303,
                    memberClient.send(request(invitationUrl), HttpResponse.BodyHandlers.ofString()).statusCode()
            );
            var current = memberClient.send(
                    request("/api/invitations/current"),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, current.statusCode());

            AUTHENTICATING_SUBJECT.set(memberSubject);
            AUTHENTICATING_EMAIL.set("someone-else@example.test");
            AUTHENTICATING_EMAIL_VERIFIED.set(true);
            var continueResponse = memberClient.send(
                    request(jsonString(current.body(), "continueUrl")),
                    HttpResponse.BodyHandlers.ofString()
            );
            var callback = completeOAuth(
                    memberClient,
                    continueResponse.headers().firstValue("location").orElseThrow()
            );

            assertEquals(302, callback.statusCode());
            assertEquals(
                    baseUri().resolve("/invitation?reason=email-mismatch").toString(),
                    callback.headers().firstValue("location").orElseThrow()
            );
            assertEquals(
                    401,
                    memberClient.send(request("/api/identity/me"), HttpResponse.BodyHandlers.ofString()).statusCode()
            );
            assertEquals(0L, jdbcClient.sql("""
                            SELECT COUNT(*) FROM external_identity_bindings
                            WHERE issuer = :issuer AND subject = :subject
                            """)
                    .param("issuer", ISSUER)
                    .param("subject", memberSubject)
                    .query(Long.class)
                    .single());
            assertEquals("PENDING", jdbcClient.sql("""
                            SELECT status FROM organization_invitations
                            WHERE normalized_email = :email
                            """)
                    .param("email", invitedEmail)
                    .query(String.class)
                    .single());
        } finally {
            AUTHENTICATING_SUBJECT.set("initial-owner");
            AUTHENTICATING_EMAIL.set("owner@example.test");
            AUTHENTICATING_EMAIL_VERIFIED.set(true);
            jdbcClient.sql("DELETE FROM organization_invitations WHERE normalized_email = :email")
                    .param("email", invitedEmail)
                    .update();
            jdbcClient.sql("DELETE FROM spring_session").update();
        }
    }

    private HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(baseUri().resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
    }

    private HttpRequest invitationMutation(String body) {
        return HttpRequest.newBuilder(baseUri().resolve("/api/invitations"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(BROWSER_MUTATION_HEADER, "1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> completeOAuth(HttpClient client, String startPath) throws Exception {
        var authorization = client.send(request(startPath), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, authorization.statusCode());
        URI providerAuthorization = URI.create(authorization.headers().firstValue("location").orElseThrow());
        var providerResponse = client.send(
                HttpRequest.newBuilder(providerAuthorization).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(302, providerResponse.statusCode());
        return client.send(
                HttpRequest.newBuilder(URI.create(providerResponse.headers().firstValue("location").orElseThrow()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private String membershipRole(String table, UUID actorId) {
        return jdbcClient.sql("SELECT role FROM " + table + " WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(String.class)
                .single();
    }

    private boolean databaseContains(String value) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM organization_invitations
                        WHERE secret_digest = :value OR normalized_email = :value
                        """)
                .param("value", value)
                .query(Long.class)
                .single() != 0;
    }

    private void assertPersistedSessionsContainNoProviderOrInvitationState() {
        List<byte[]> attributes = jdbcClient.sql("SELECT attribute_bytes FROM spring_session_attributes")
                .query(byte[].class)
                .list();
        assertFalse(attributes.isEmpty());
        for (byte[] attribute : attributes) {
            String serialized = new String(attribute, ISO_8859_1);
            assertFalse(serialized.contains(PROVIDER_ID_TOKEN_MARKER));
            assertFalse(serialized.contains(PROVIDER_ACCESS_TOKEN));
            assertFalse(serialized.contains(InvitationSessionState.class.getName()));
        }
    }

    private void deleteInvitedMember(String subject, String email) {
        jdbcClient.sql("DELETE FROM organization_invitations WHERE normalized_email = :email")
                .param("email", email)
                .update();
        jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = :subject
                        """)
                .param("issuer", ISSUER)
                .param("subject", subject)
                .query(UUID.class)
                .optional()
                .ifPresent(actorId -> {
                    jdbcClient.sql("DELETE FROM workspace_memberships WHERE actor_id = :actorId")
                            .param("actorId", actorId)
                            .update();
                    jdbcClient.sql("DELETE FROM organization_memberships WHERE actor_id = :actorId")
                            .param("actorId", actorId)
                            .update();
                    jdbcClient.sql("""
                                    DELETE FROM external_identity_bindings
                                    WHERE issuer = :issuer AND subject = :subject
                                    """)
                            .param("issuer", ISSUER)
                            .param("subject", subject)
                            .update();
                    jdbcClient.sql("DELETE FROM actors WHERE id = :actorId")
                            .param("actorId", actorId)
                            .update();
                });
    }

    private static String jsonString(String body, String field) {
        var matcher = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("missing JSON field " + field + " in " + body);
        }
        return matcher.group(1);
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + port);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private static String sessionCookie(CookieManager manager) {
        return manager.getCookieStore().getCookies().stream()
                .filter(cookie -> "SESSION".equals(cookie.getName()))
                .findFirst()
                .map(HttpCookie::getValue)
                .orElseThrow(() -> new IllegalStateException(manager.getCookieStore().getCookies().toString()));
    }

    private static HttpServer startIdentityServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/.well-known/openid-configuration", BrowserAuthenticationIntegrationTest::metadata);
            server.createContext("/authorize", BrowserAuthenticationIntegrationTest::authorize);
            server.createContext("/token", BrowserAuthenticationIntegrationTest::token);
            server.createContext("/jwks", BrowserAuthenticationIntegrationTest::jwks);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start test identity server", exception);
        }
    }

    private static void metadata(HttpExchange exchange) throws IOException {
        String issuer = "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
        json(exchange, """
                {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",\
                "jwks_uri":"%s/jwks","response_types_supported":["code"],\
                "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],\
                "scopes_supported":["openid"],"code_challenge_methods_supported":["S256"],\
                "token_endpoint_auth_methods_supported":["client_secret_basic"],\
                "claims_supported":["iss","sub","aud","exp","iat","email","email_verified"]}
                """.formatted(issuer, issuer, issuer, issuer));
    }

    private static void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> parameters = query(exchange.getRequestURI());
        String code = UUID.randomUUID().toString();
        AUTHORIZATION_GRANTS.put(code, new AuthorizationGrant(
                parameters.get("nonce"),
                AUTHENTICATING_SUBJECT.get(),
                AUTHENTICATING_EMAIL.get(),
                AUTHENTICATING_EMAIL_VERIFIED.get(),
                parameters.get("code_challenge")
        ));
        String separator = parameters.get("redirect_uri").contains("?") ? "&" : "?";
        String location = parameters.get("redirect_uri") + separator
                + "code=" + encode(code) + "&state=" + encode(parameters.get("state"));
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void token(HttpExchange exchange) throws IOException {
        String form = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        Map<String, String> parameters = query(URI.create("http://test/?" + form));
        AuthorizationGrant grant = AUTHORIZATION_GRANTS.remove(parameters.get("code"));
        if (grant == null || !grant.codeChallenge().equals(s256(parameters.get("code_verifier")))) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(grant.subject())
                .audience(CLIENT_ID)
                .claim("email", grant.email())
                .claim("email_verified", grant.emailVerified())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", grant.nonce())
                .claim("session_leak_marker", PROVIDER_ID_TOKEN_MARKER)
                .build();
        String idToken = signedToken(claims);
        json(exchange, """
                {"access_token":"%s","token_type":"Bearer","expires_in":300,\
                "scope":"openid","id_token":"%s"}
                """.formatted(PROVIDER_ACCESS_TOKEN, idToken));
    }

    private static void jwks(HttpExchange exchange) throws IOException {
        json(exchange, new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> query(URI uri) {
        var result = new HashMap<String, String>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return result;
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, UTF_8);
    }

    private static String signedToken(JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                    claims
            );
            jwt.sign(new RSASSASigner(SIGNING_KEY));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("failed to sign test ID token", exception);
        }
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("failed to generate test signing key", exception);
        }
    }

    private record AuthorizationGrant(
            String nonce,
            String subject,
            String email,
            boolean emailVerified,
            String codeChallenge
    ) {
    }
}