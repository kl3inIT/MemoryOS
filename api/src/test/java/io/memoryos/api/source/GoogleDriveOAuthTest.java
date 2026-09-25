package io.memoryos.api.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveAccountClient;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.adapter.googledrive.GoogleDriveProviderProperties;
import io.memoryos.connector.adapter.googledrive.RestGoogleDriveAccountClient;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IdentityContext;
import io.memoryos.shared.TenantId;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

class GoogleDriveOAuthTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private GoogleDriveAccountClient accounts;
    private GoogleDriveAuthorizationService authorizations;
    private GoogleDriveOAuthCallbackController callback;
    private MockHttpSession session;
    private IdentityContext identity;
    private GoogleDriveAuthorizationSessionState state;
    private RSAKey signingKey;
    private String issuer;
    private String nonce;
    private String clientId = "drive-client.apps.googleusercontent.com";
    private String audience = clientId;
    private final AtomicInteger exchanges = new AtomicInteger();
    private Map<String, String> exchangedForm;
    private CredentialId credential;

    @BeforeEach
    void setup() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        // The API wires the provider bundle's real client; its protocol rules are covered in RestGoogleDriveAccountClientTest.
        accounts = new RestGoogleDriveAccountClient(new GoogleDriveProviderProperties(URI.create(issuer + "/token"),
                URI.create(issuer + "/drive/v3"), null, null, null, null, null, null, 0, 0, 0, 0, 0, 0,
                URI.create("http://127.0.0.1:8080/login/oauth2/code/google-drive"), URI.create(issuer + "/authorize"),
                null, URI.create(issuer + "/jwks"), URI.create(issuer)), mapper);
        authorizations = mock(GoogleDriveAuthorizationService.class);
        callback = new GoogleDriveOAuthCallbackController(authorizations, accounts);
        credential = new CredentialId(UUID.randomUUID());
        when(authorizations.complete(any(), any(), any())).thenReturn(credential);
        when(authorizations.oauthClient(any(), any())).thenAnswer(_ -> oauthClient(clientId));
        identity = new IdentityContext(new ActorId(UUID.randomUUID()));
        session = new MockHttpSession();
        authenticate(identity);
        state = GoogleDriveAuthorizationSessionState.start(request(), identity,
                new GoogleDriveAuthorizationService.Preparation(new TenantId(UUID.randomUUID()), "Drive", null, null,
                        UUID.randomUUID(), "opaque-encrypted-client-snapshot"));
        nonce = state.nonce();
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/token", exchange -> {
            exchanges.incrementAndGet();
            exchangedForm = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body;
            try {
                body = mapper.writeValueAsBytes(Map.of("access_token", "test-access-secret", "refresh_token", "test-refresh-secret",
                        "token_type", "Bearer", "expires_in", 3600, "id_token", idToken()));
            } catch (Exception exception) { throw new java.io.IOException(exception); }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/drive/v3/about", exchange -> {
            assertEquals("Bearer test-access-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("user(emailAddress)", query(exchange.getRequestURI().getRawQuery()).get("fields"));
            byte[] body = mapper.writeValueAsBytes(Map.of("user", Map.of("emailAddress", "owner@example.com")));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    @AfterEach
    void teardown() { server.stop(0); }

    @Test
    void completesPkceNonceBoundCallbackOnceWithoutReplacingActorOrSavingTokens() throws Exception {
        URI launch = URI.create(accounts.authorizationUrl(clientId, state.consent()));
        Map<String, String> parameters = query(launch.getRawQuery());
        assertEquals(state.challenge(), parameters.get("code_challenge"));
        assertEquals("S256", parameters.get("code_challenge_method"));
        assertTrue(parameters.get("scope").contains("https://www.googleapis.com/auth/documents.readonly"));
        assertEquals(clientId, parameters.get("client_id"));
        assertFalse(parameters.containsKey("client_secret"));
        var response = deliver(state.state());
        assertEquals("/admin/sources/new/google-drive?googleDrive=connected&credentialId=" + credential.value(), response.getRedirectedUrl());
        assertEquals(state.verifier(), exchangedForm.get("code_verifier"));
        assertEquals("http://127.0.0.1:8080/login/oauth2/code/google-drive", exchangedForm.get("redirect_uri"));
        assertEquals(clientId, exchangedForm.get("client_id"));
        assertEquals("test-client-secret", exchangedForm.get("client_secret"));
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertEquals(identity, context.getAuthentication().getPrincipal());
        for (var names = session.getAttributeNames(); names.hasMoreElements();) {
            Object attribute = session.getAttribute(names.nextElement());
            var bytes = new java.io.ByteArrayOutputStream();
            try (var serialized = new java.io.ObjectOutputStream(bytes)) { serialized.writeObject(attribute); }
            String stored = bytes.toString(StandardCharsets.ISO_8859_1);
            assertFalse(stored.contains("test-refresh-secret"));
            assertFalse(stored.contains("test-access-secret"));
            assertFalse(stored.contains("test-client-secret"));
        }
        assertEquals("/admin/sources/new/google-drive?googleDrive=authorization-failed", deliver(state.state()).getRedirectedUrl());
        assertEquals(1, exchanges.get());
        verify(authorizations, times(1)).complete(eq(identity.actorId()), any(), any());
    }

    @Test
    void unrelatedStateDoesNotConsumeValidContinuationAndChangedActorCannotUseIt() throws Exception {
        assertTrue(deliver("wrong-state").getRedirectedUrl().endsWith("authorization-failed"));
        authenticate(new IdentityContext(new ActorId(UUID.randomUUID())));
        assertTrue(deliver(state.state()).getRedirectedUrl().endsWith("authorization-failed"));
        assertEquals(0, exchanges.get());
        verifyNoInteractions(authorizations);
    }

    @Test
    void rejectsValidlySignedTokenWithWrongNonce() throws Exception {
        nonce = "different-flow";
        assertTrue(deliver(state.state()).getRedirectedUrl().endsWith("authorization-failed"));
        verify(authorizations, never()).complete(any(), any(), any());
    }

    @Test
    void rejectsValidlySignedTokenForAnotherOAuthClient() throws Exception {
        audience = "another-client";
        assertTrue(deliver(state.state()).getRedirectedUrl().endsWith("authorization-failed"));
        verify(authorizations, never()).complete(any(), any(), any());
    }

    @Test
    void requiresExistingActorSessionAndRejectsWeakRootRevisionPreconditions() {
        assertThrows(SourceException.class, () -> GoogleDriveAuthorizationSessionState.start(new MockHttpServletRequest(), identity, state.preparation()));
        assertEquals(7, GoogleDriveSourceController.revision("\"7\""));
        assertThrows(SourceException.class, () -> GoogleDriveSourceController.revision("W/\"7\""));
        assertThrows(SourceException.class, () -> GoogleDriveSourceController.revision("*"));
        assertThrows(SourceException.class, () -> GoogleDriveSourceController.revision("\"9223372036854775808\""));
    }

    @Test
    void audienceValidationUsesEachConsentAppRatherThanCachedFirstApp() throws Exception {
        assertEquals("connected", query(URI.create(deliver(state.state()).getRedirectedUrl()).getRawQuery()).get("googleDrive"));
        clientId = "second-client.apps.googleusercontent.com";
        state = GoogleDriveAuthorizationSessionState.start(request(), identity, state.preparation());
        nonce = state.nonce();
        assertTrue(deliver(state.state()).getRedirectedUrl().endsWith("authorization-failed"));
        verify(authorizations, times(1)).complete(any(), any(), any());
        audience = clientId;
        state = GoogleDriveAuthorizationSessionState.start(request(), identity, state.preparation());
        nonce = state.nonce();
        assertEquals("connected", query(URI.create(deliver(state.state()).getRedirectedUrl()).getRawQuery()).get("googleDrive"));
        assertEquals(clientId, exchangedForm.get("client_id"));
        verify(authorizations, times(2)).complete(any(), any(), any());
    }

    @Test
    void failedReconnectReturnsOnlyTheSelectedCredentialAndPreservesItsConsentBinding() throws Exception {
        var preparation = new GoogleDriveAuthorizationService.Preparation(new TenantId(state.tenantId()), "Shared Drive",
                credential, 7L, UUID.randomUUID(), "encrypted-reconnect-client");
        state = GoogleDriveAuthorizationSessionState.start(request(), identity, preparation);
        when(authorizations.oauthClient(any(), eq(preparation))).thenThrow(
                new IllegalStateException("provider body with must-not-escape secret"));
        var response = deliver(state.state());
        assertEquals("/admin/sources/new/google-drive?googleDrive=authorization-failed&credentialId=" + credential.value(),
                response.getRedirectedUrl());
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        verify(authorizations).oauthClient(identity.actorId(), preparation);
        verify(authorizations, never()).complete(any(), any(), any());
        assertEquals(0, exchanges.get());
    }

    private static GoogleDriveOAuthClient oauthClient(String clientId) {
        return new GoogleDriveOAuthClient(clientId, "test-client-secret".getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse deliver(String oauthState) throws Exception {
        var request = request(); request.setParameter("state", oauthState); request.setParameter("code", "authorization-code");
        var response = new MockHttpServletResponse(); callback.callback(request, response); return response;
    }
    private MockHttpServletRequest request() { var request = new MockHttpServletRequest(); request.setSession(session); return request; }
    private void authenticate(IdentityContext principal) {
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(new ActorAuthenticationToken(principal));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }
    private String idToken() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("google-subject").audience(audience)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", nonce).claim("email", "owner@example.com").claim("email_verified", true).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(signingKey)); return jwt.serialize();
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
