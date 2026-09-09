package io.memoryos.api.source;

import io.memoryos.connector.GoogleDriveAuthorizationService.Grant;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveOAuthClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class GoogleDriveAccountClient {
    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final ObjectMapper CLIENT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final GoogleDriveOAuthProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final JdkClientHttpRequestFactory requestFactory;
    private NimbusJwtDecoder decoder;

    private synchronized NimbusJwtDecoder decoder() {
        if (decoder == null) {
            decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                    .restOperations(new RestTemplate(requestFactory)).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuerUri().toString()));
        }
        return decoder;
    }

    public GoogleDriveAccountClient(GoogleDriveOAuthProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().requestFactory(requestFactory).build();
    }

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
            String callback = properties.redirectUri().toString();
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

    private static void allowedEndpoint(JsonNode web, String field, Set<String> allowed) {
        if (web.has(field) && (!web.path(field).isString() || !allowed.contains(web.path(field).asString()))) {
            throw GoogleDriveException.invalidOAuthClient();
        }
    }

    public String authorizationUrl(GoogleDriveAuthorizationSessionState state, String clientId) {
        properties.requireConfigured();
        return UriComponentsBuilder.fromUri(properties.authorizationUri())
                .queryParam("client_id", clientId).queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code").queryParam("scope", String.join(" ", requestedScopes()))
                .queryParam("state", state.state()).queryParam("nonce", state.nonce())
                .queryParam("code_challenge", state.challenge()).queryParam("code_challenge_method", "S256")
                .queryParam("access_type", "offline").queryParam("prompt", "consent select_account")
                .queryParam("include_granted_scopes", "false").build().encode().toUriString();
    }

    public Grant exchange(String code, GoogleDriveAuthorizationSessionState state, GoogleDriveOAuthClient oauthClient) {
        try { return exchangeChecked(code, state, oauthClient); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private Grant exchangeChecked(String code, GoogleDriveAuthorizationSessionState state, GoogleDriveOAuthClient oauthClient) {
        properties.requireConfigured();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code"); form.add("code", code);
        byte[] secret = oauthClient.clientSecret();
        try {
            form.add("client_id", oauthClient.clientId());
            form.add("client_secret", new String(secret, StandardCharsets.UTF_8));
        } finally { Arrays.fill(secret, (byte) 0); }
        form.add("redirect_uri", properties.redirectUri().toString()); form.add("code_verifier", state.verifier());
        JsonNode token = json(client.post().uri(properties.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form));
        if (!"Bearer".equalsIgnoreCase(text(token, "token_type"))) throw invalid();
        var identity = decoder().decode(text(token, "id_token"));
        if (new JwtAudienceValidator(oauthClient.clientId()).validate(identity).hasErrors()) throw invalid();
        String nonce = identity.getClaimAsString("nonce");
        String authorizedParty = identity.getClaimAsString("azp");
        String email = identity.getClaimAsString("email");
        if (nonce == null || !GoogleDriveAuthorizationSessionState.equal(state.nonce(), nonce)
                || !Boolean.TRUE.equals(identity.getClaimAsBoolean("email_verified"))
                || identity.getExpiresAt() == null || identity.getIssuedAt() == null
                || identity.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(60))
                || identity.getSubject() == null || identity.getSubject().isBlank() || email == null || email.isBlank()
                || (identity.getAudience().size() > 1 && authorizedParty == null)
                || (authorizedParty != null && !oauthClient.clientId().equals(authorizedParty))) throw invalid();
        String accessToken = text(token, "access_token");
        String atHash = identity.getClaimAsString("at_hash");
        if (atHash != null && !GoogleDriveAuthorizationSessionState.equal(accessTokenHash(accessToken), atHash)) throw invalid();
        String accountEmail = resolve(accessToken);
        if (!email.equalsIgnoreCase(accountEmail)) throw invalid();
        Set<String> scopes = token.path("scope").isString()
                ? new HashSet<>(Arrays.asList(text(token, "scope").split(" +"))) : requestedScopes();
        byte[] refreshToken = text(token, "refresh_token").getBytes(StandardCharsets.UTF_8);
        try { return new Grant(identity.getSubject(), email, scopes, refreshToken); }
        finally { Arrays.fill(refreshToken, (byte) 0); }
    }

    public String resolve(String accessToken) {
        URI uri = UriComponentsBuilder.fromUri(properties.driveApiBaseUrl()).path("/about")
                .queryParam("fields", "user(emailAddress)").build().encode().toUri();
        JsonNode response = json(client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
        return text(response.path("user"), "emailAddress");
    }

    public void revoke(byte[] refreshToken) {
        try {
            if (refreshToken.length == 0) return;
            var form = new LinkedMultiValueMap<String, String>();
            form.add("token", new String(refreshToken, StandardCharsets.UTF_8));
            client.post().uri(properties.revocationUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).exchange((request, response) -> null);
        } catch (RuntimeException ignored) {
            // The local disconnect has committed. Remote revocation cannot restore local authority.
        } finally { Arrays.fill(refreshToken, (byte) 0); }
    }

    private JsonNode json(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((sent, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw invalid();
            byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
            try {
                if (bytes.length > MAX_RESPONSE_BYTES) throw invalid();
                return mapper.readTree(bytes);
            } finally { Arrays.fill(bytes, (byte) 0); }
        });
    }

    private static Set<String> requestedScopes() {
        var scopes = new java.util.TreeSet<>(GoogleDriveAuthorizationService.REQUIRED_SCOPES);
        scopes.add("email"); return scopes;
    }

    private static String accessTokenHash(String token) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, hash.length / 2));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > 16384) throw invalid();
        return value.asString();
    }
    private static RuntimeException invalid() { return new IllegalStateException("Google authorization response is invalid"); }
}
