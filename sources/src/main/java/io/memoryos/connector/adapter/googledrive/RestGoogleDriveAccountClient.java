package io.memoryos.connector.adapter.googledrive;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.services.drive.Drive;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.memoryos.connector.GoogleDriveAccountClient;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveAuthorizationService.Grant;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.shared.Sha256;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google's OAuth endpoints for connecting a Drive account. Endpoints are validated when an operation runs, not at
 * startup, so a deployment without Google settings still starts; redirects are never followed, so a token endpoint
 * cannot forward the client secret elsewhere.
 *
 * <p>The code is exchanged by Google's OAuth library and the account read by Google's Drive client, on
 * {@link GoogleTransport} (MEM-226). The ID token is still verified by the Nimbus decoder of Spring Security, which
 * keeps the checks written here (audience, nonce, authorized party, verified email, issue time, {@code at_hash});
 * Google's own verifier fetches its certificates with a request that cannot be bounded or kept from a redirect. The
 * key set is fetched here, within a bound, and handed to the decoder. One deadline covers the three requests of a
 * consent.
 */
public final class RestGoogleDriveAccountClient implements GoogleDriveAccountClient {
    private static final String CALLBACK_PATH = "/login/oauth2/code/google-drive";
    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** One consent: the token exchange, the key set and the account read together. */
    private static final Duration CONSENT_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper CLIENT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final GoogleDriveProviderProperties properties;
    private final ObjectMapper mapper;
    private final GoogleTransport transport = new GoogleTransport(Duration.ofSeconds(3), REQUEST_TIMEOUT);

    public RestGoogleDriveAccountClient(GoogleDriveProviderProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** The decoder of one consent, over the key set as Google serves it now. */
    private NimbusJwtDecoder decoder(Deadline deadline) {
        endpoint(properties.jwkSetUri());
        byte[] keys = send(() -> transport.transport().createRequestFactory(transport.initializer(null))
                .buildGetRequest(new GenericUrl(properties.jwkSetUri().toString())).execute(), deadline);
        try {
            var decoder = NimbusJwtDecoder.withJwkSource(
                    new ImmutableJWKSet<>(JWKSet.parse(new String(keys, StandardCharsets.UTF_8)))).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(endpoint(properties.issuerUri()).toString()));
            return decoder;
        } catch (ParseException exception) {
            throw invalid();
        }
    }

    @Override
    public @Nullable GoogleDriveOAuthClient parseClient(@Nullable String json) {
        if (json == null) return null;
        if (json.isBlank() || json.length() > 16384) throw GoogleDriveException.invalidOAuthClient();
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        try {
            if (payload.length > 16384) throw GoogleDriveException.invalidOAuthClient();
            JsonNode root = CLIENT_JSON.readTree(payload);
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("web").isObject()) {
                throw GoogleDriveException.invalidOAuthClient();
            }
            JsonNode web = root.path("web");
            JsonNode redirects = web.path("redirect_uris");
            if (!redirects.isArray() || redirects.isEmpty() || redirects.size() > 100) throw GoogleDriveException.invalidOAuthClient();
            boolean matches = false;
            String callback = redirectUri().toString();
            for (JsonNode redirect : redirects) {
                if (!redirect.isString() || redirect.asString().length() > 2048) throw GoogleDriveException.invalidOAuthClient();
                if (callback.equals(redirect.asString())) matches = true;
            }
            if (!matches) throw GoogleDriveException.invalidOAuthClient();
            allowedEndpoint(web, "auth_uri", Set.of("https://accounts.google.com/o/oauth2/auth", "https://accounts.google.com/o/oauth2/v2/auth"));
            allowedEndpoint(web, "token_uri", Set.of("https://oauth2.googleapis.com/token"));
            allowedEndpoint(web, "auth_provider_x509_cert_url", Set.of("https://www.googleapis.com/oauth2/v1/certs"));
            byte[] secret = text(web, "client_secret").getBytes(StandardCharsets.UTF_8);
            try { return new GoogleDriveOAuthClient(text(web, "client_id"), secret); }
            finally { Arrays.fill(secret, (byte) 0); }
        } catch (RuntimeException exception) {
            if (exception instanceof GoogleDriveException drive && "GOOGLE_DRIVE_NOT_CONFIGURED".equals(drive.code())) throw drive;
            throw GoogleDriveException.invalidOAuthClient();
        } finally { Arrays.fill(payload, (byte) 0); }
    }

    @Override
    public void requireConfigured() {
        redirectUri();
        endpoint(properties.authorizationUri());
        endpoint(properties.tokenUri());
        endpoint(properties.revocationUri());
        endpoint(properties.driveApiBaseUrl());
        endpoint(properties.jwkSetUri());
        endpoint(properties.issuerUri());
    }

    @Override
    public String authorizationUrl(String clientId, Consent consent) {
        requireConfigured();
        return UriComponentsBuilder.fromUri(properties.authorizationUri())
                .queryParam("client_id", clientId).queryParam("redirect_uri", redirectUri())
                .queryParam("response_type", "code").queryParam("scope", String.join(" ", requestedScopes()))
                .queryParam("state", consent.state()).queryParam("nonce", consent.nonce())
                .queryParam("code_challenge", consent.codeChallenge()).queryParam("code_challenge_method", "S256")
                .queryParam("access_type", "offline").queryParam("prompt", "consent select_account")
                .queryParam("include_granted_scopes", "false").build().encode().toUriString();
    }

    @Override
    public Grant exchange(String code, String codeVerifier, String nonce, GoogleDriveOAuthClient oauthClient) {
        try { return exchangeChecked(code, codeVerifier, nonce, oauthClient); }
        // A deployment that is not configured says so; anything else is an answer that cannot be trusted.
        catch (GoogleDriveException configuration) { throw configuration; }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private Grant exchangeChecked(String code, String codeVerifier, String expectedNonce, GoogleDriveOAuthClient oauthClient) {
        requireConfigured();
        var deadline = new Deadline();
        byte[] secret = oauthClient.clientSecret();
        JsonNode token;
        try {
            var request = new GoogleAuthorizationCodeTokenRequest(transport.transport(), GoogleTransport.JSON,
                    properties.tokenUri().toString(), oauthClient.clientId(), new String(secret, StandardCharsets.UTF_8),
                    code, redirectUri().toString());
            request.set("code_verifier", codeVerifier);
            request.setRequestInitializer(transport.initializer(null));
            token = json(request::executeUnparsed, deadline);
        } finally { Arrays.fill(secret, (byte) 0); }
        if (!"Bearer".equalsIgnoreCase(text(token, "token_type"))) throw invalid();
        var identity = decoder(deadline).decode(text(token, "id_token"));
        if (new JwtAudienceValidator(oauthClient.clientId()).validate(identity).hasErrors()) throw invalid();
        String nonce = identity.getClaimAsString("nonce");
        String authorizedParty = identity.getClaimAsString("azp");
        String email = identity.getClaimAsString("email");
        List<String> audience = identity.getAudience();
        if (nonce == null || !equal(expectedNonce, nonce)
                || !Boolean.TRUE.equals(identity.getClaimAsBoolean("email_verified"))
                || identity.getExpiresAt() == null || identity.getIssuedAt() == null
                || identity.getIssuedAt().isAfter(Instant.now().plusSeconds(60))
                || identity.getSubject() == null || identity.getSubject().isBlank() || email == null || email.isBlank()
                || audience == null || (audience.size() > 1 && authorizedParty == null)
                || (authorizedParty != null && !oauthClient.clientId().equals(authorizedParty))) throw invalid();
        String accessToken = text(token, "access_token");
        String atHash = identity.getClaimAsString("at_hash");
        if (atHash != null && !equal(accessTokenHash(accessToken), atHash)) throw invalid();
        String accountEmail = accountEmail(accessToken, deadline);
        if (!email.equalsIgnoreCase(accountEmail)) throw invalid();
        Set<String> scopes = token.path("scope").isString()
                ? new HashSet<>(Arrays.asList(text(token, "scope").split(" +"))) : requestedScopes();
        byte[] refreshToken = text(token, "refresh_token").getBytes(StandardCharsets.UTF_8);
        try { return new Grant(identity.getSubject(), email, scopes, refreshToken); }
        finally { Arrays.fill(refreshToken, (byte) 0); }
    }

    private String accountEmail(String accessToken, Deadline deadline) {
        URI base = properties.driveApiBaseUrl();
        var drive = new Drive.Builder(transport.transport(), GoogleTransport.JSON, transport.initializer(accessToken))
                .setRootUrl(GoogleTransport.root(base)).setServicePath(GoogleTransport.servicePath(base))
                .setApplicationName("MemoryOS").build();
        JsonNode response = json(() -> drive.about().get().setFields("user(emailAddress)").executeUnparsed(), deadline);
        return text(response.path("user"), "emailAddress");
    }

    @Override
    public void revoke(byte[] refreshToken) {
        try {
            if (refreshToken.length == 0) return;
            var form = new UrlEncodedContent(Map.of("token", new String(refreshToken, StandardCharsets.UTF_8)));
            URI revocation = endpoint(properties.revocationUri());
            send(() -> transport.transport().createRequestFactory(transport.initializer(null))
                    .buildPostRequest(new GenericUrl(revocation.toString()), form).execute(), new Deadline());
        } catch (RuntimeException ignored) {
            // The local disconnect has committed. Remote revocation cannot restore local authority.
        } finally { Arrays.fill(refreshToken, (byte) 0); }
    }

    private URI redirectUri() {
        URI callback = properties.redirectUri();
        if (callback == null || !CALLBACK_PATH.equals(callback.getPath())) throw GoogleDriveException.notConfigured();
        return endpoint(callback);
    }

    /** HTTPS, or HTTP on a loopback host for local runs, with no user info, query or fragment. */
    private static URI endpoint(URI uri) {
        boolean secure = "https".equals(uri.getScheme()) && uri.getHost() != null;
        boolean loopback = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if ((!secure && !loopback) || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw GoogleDriveException.notConfigured();
        }
        return uri;
    }

    private static void allowedEndpoint(JsonNode web, String field, Set<String> allowed) {
        if (web.has(field) && (!web.path(field).isString() || !allowed.contains(web.path(field).asString()))) {
            throw GoogleDriveException.invalidOAuthClient();
        }
    }

    private JsonNode json(GoogleTransport.Call request, Deadline deadline) {
        byte[] bytes = send(request, deadline);
        try {
            return mapper.readTree(bytes);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    /** One request of a consent: a bounded answer, within what the consent's deadline has left. */
    private byte[] send(GoogleTransport.Call request, Deadline deadline) {
        try {
            return transport.exchange(request, MAX_RESPONSE_BYTES, Math.min(REQUEST_TIMEOUT.toNanos(), deadline.remaining()));
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private static final class Deadline {
        private final long at = System.nanoTime() + CONSENT_TIMEOUT.toNanos();

        long remaining() {
            long left = at - System.nanoTime();
            if (left <= 0) throw invalid();
            return left;
        }
    }

    private static Set<String> requestedScopes() {
        var scopes = new TreeSet<>(GoogleDriveAuthorizationService.REQUIRED_SCOPES);
        scopes.add("email"); return scopes;
    }

    private static String accessTokenHash(String token) {
        byte[] hash = Sha256.digest(token.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, hash.length / 2));
    }

    private static boolean equal(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > 16384) throw invalid();
        return value.asString();
    }

    private static RuntimeException invalid() { return new IllegalStateException("Google authorization response is invalid"); }

    @Override public String toString() { return "RestGoogleDriveAccountClient[redacted]"; }
}
