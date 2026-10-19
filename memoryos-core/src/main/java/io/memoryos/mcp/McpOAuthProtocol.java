package io.memoryos.mcp;

import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenRevocationRequest;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.client.ClientDeleteRequest;
import com.nimbusds.oauth2.sdk.client.ClientInformation;
import com.nimbusds.oauth2.sdk.client.ClientMetadata;
import com.nimbusds.oauth2.sdk.client.ClientRegistrationRequest;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.token.TypelessToken;
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils;
import io.memoryos.shared.OutboundHttp;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minidev.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth protocol calls for MCP authorization (MCP {@code 2025-11-25} authorization, RFC 9728, RFC 8414, RFC 7591,
 * RFC 8707). Nimbus builds and parses the OAuth messages (MEM-227); they are sent over {@link OutboundHttp}, so
 * redirects are never followed and responses are bounded. Every JSON answer is read strictly here before Nimbus reads
 * it, and the values kept are bounded. Protected-resource metadata and the challenge, which Nimbus has no class for,
 * stay here. Upstream bodies never cross this boundary. Endpoints follow the MCP endpoint policy: HTTP(S) including
 * internal hosts, without credentials or fragments.
 */
@Component
public final class McpOAuthProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(McpOAuthProtocol.class);
    private static final Pattern OAUTH_ERROR_CODE = Pattern.compile("[a-z_]{1,64}");
    static final int MAX_RESPONSE_BYTES = 65536;
    private static final int MAX_AUTHORIZATION_SERVERS = 4;
    private static final int MAX_TOKEN_CHARACTERS = 16384;
    private static final long MAX_EXPIRES_IN_SECONDS = 366L * 24 * 3600;
    private static final String PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource";
    /** Parameters MemoryOS sets itself; an administrator's additional parameter never replaces one. */
    private static final Set<String> AUTHORIZATION_PARAMETERS = Set.of("response_type", "client_id", "redirect_uri", "state",
            "code_challenge", "code_challenge_method", "scope", "resource");
    private static final Pattern AUTH_PARAMETER = Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[^\\s,]+)");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final RestClient http;

    public McpOAuthProtocol(@Value("${memoryos.mcp.request-timeout:30s}") Duration requestTimeout) {
        this.http = OutboundHttp.builder(new OutboundHttp.Limits(requestTimeout, MAX_RESPONSE_BYTES)).build();
    }

    /** A {@code WWW-Authenticate: Bearer} challenge from an MCP server. */
    public record Challenge(@Nullable URI resourceMetadata, @Nullable String scope) {}

    public record ProtectedResource(URI metadataUrl, String resource, List<String> authorizationServers,
                                    List<String> scopesSupported) {}

    public record AuthorizationServer(String issuer, URI authorizationEndpoint, URI tokenEndpoint,
                                      @Nullable URI registrationEndpoint, @Nullable URI revocationEndpoint,
                                      List<String> tokenEndpointAuthMethods, boolean clientIdMetadataDocumentSupported,
                                      boolean issParameterSupported) {}

    /** What the administrator reviews; nothing is persisted by discovery. */
    public record Discovery(ProtectedResource protectedResource, @Nullable String challengedScope,
                            List<AuthorizationServer> authorizationServers) {}

    public record Client(String clientId, @Nullable String clientSecret, McpTokenEndpointAuthMethod method) {
        @Override public @NonNull String toString() { return "McpOAuthClient[redacted]"; }
    }

    public record Registration(Client client, @Nullable URI registrationClientUri, @Nullable String registrationAccessToken) {
        @Override public @NonNull String toString() { return "McpOAuthRegistration[redacted]"; }
    }

    public record Tokens(String accessToken, @Nullable String refreshToken, @Nullable Instant expiresAt, @Nullable String scope) {
        @Override public @NonNull String toString() { return "McpOAuthTokens[redacted]"; }
    }

    private record Response(int status, HttpHeaders headers, byte[] body) {}

    public Discovery discover(String serverUrl) {
        URI server = McpClients.endpoint(serverUrl);
        Challenge challenge = unauthenticatedChallenge(server);
        List<URI> metadataUrls = challenge.resourceMetadata() != null
                ? List.of(challenge.resourceMetadata()) : protectedResourceMetadataUrls(server);
        URI root = URI.create(server.getScheme() + "://" + server.getRawAuthority());
        ProtectedResource resource = null;
        for (URI url : metadataUrls) {
            JsonNode document = optionalJson(url);
            if (document == null) continue;
            // The spec allows the metadata to live at the root, and lists an origin-only URI as a valid
            // canonical resource, so the root document may identify the origin instead of the MCP endpoint.
            boolean rootDocument = url.getRawPath().equals(PROTECTED_RESOURCE);
            resource = protectedResource(url, document, server, rootDocument ? root : null);
            break;
        }
        if (resource == null)
            throw McpException.oauthDiscoveryFailed("The MCP server does not publish OAuth protected-resource metadata.");
        var servers = new ArrayList<AuthorizationServer>();
        for (String issuer : resource.authorizationServers()) {
            AuthorizationServer found = authorizationServer(issuer);
            if (found != null) servers.add(found);
        }
        if (servers.isEmpty())
            throw McpException.oauthDiscoveryFailed("No authorization server of this MCP server publishes usable metadata with PKCE S256.");
        return new Discovery(resource, challenge.scope(), List.copyOf(servers));
    }

    /** RFC 7591 registration with the redirect URI; prefers basic, then post, then public-client authentication. */
    public Registration register(AuthorizationServer server, URI redirectUri, String clientName) {
        if (server.registrationEndpoint() == null) throw McpException.oauthRegistrationFailed();
        var methods = server.tokenEndpointAuthMethods();
        McpTokenEndpointAuthMethod requested = methods.isEmpty() || methods.contains("client_secret_basic")
                ? McpTokenEndpointAuthMethod.CLIENT_SECRET_BASIC
                : methods.contains("client_secret_post") ? McpTokenEndpointAuthMethod.CLIENT_SECRET_POST
                : methods.contains("none") ? McpTokenEndpointAuthMethod.NONE : null;
        if (requested == null) throw McpException.oauthRegistrationFailed();
        var metadata = new ClientMetadata();
        metadata.setName(clientName);
        metadata.setRedirectionURI(redirectUri);
        metadata.setGrantTypes(Set.of(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN));
        metadata.setResponseTypes(Set.of(ResponseType.CODE));
        metadata.setTokenEndpointAuthMethod(ClientAuthenticationMethod.parse(requested.wireValue()));
        Response response;
        try {
            response = send(new ClientRegistrationRequest(server.registrationEndpoint(), metadata, null).toHTTPRequest(), true);
        } catch (McpException unavailable) {
            throw McpException.oauthRegistrationFailed();
        }
        if (response.status() != 200 && response.status() != 201) throw McpException.oauthRegistrationFailed();
        try {
            ClientInformation registered = ClientInformation.parse(nimbusObject(response.body()));
            String clientId = bounded(registered.getID().getValue(), 2048);
            String secret = registered.getSecret() == null ? null : bounded(registered.getSecret().getValue(), MAX_TOKEN_CHARACTERS);
            ClientAuthenticationMethod method = registered.getMetadata().getTokenEndpointAuthMethod();
            McpTokenEndpointAuthMethod granted = method == null ? requested : McpTokenEndpointAuthMethod.fromWire(method.getValue());
            if ((granted == McpTokenEndpointAuthMethod.NONE) != (secret == null)) throw McpException.oauthRegistrationFailed();
            // RFC 7592 asks for an absolute URL; Canva answers with a path ("/register/<id>"), resolved against the
            // registration endpoint as a browser resolves a link.
            URI clientUri = registered.getRegistrationURI();
            BearerAccessToken registrationToken = registered.getRegistrationAccessToken();
            return new Registration(new Client(clientId, secret, granted),
                    clientUri == null ? null : endpoint(server.registrationEndpoint().resolve(clientUri).toString(), false),
                    registrationToken == null ? null : bounded(registrationToken.getValue(), MAX_TOKEN_CHARACTERS));
        } catch (ParseException | RuntimeException invalid) {
            throw McpException.oauthRegistrationFailed();
        }
    }

    public URI authorizationUrl(URI authorizationEndpoint, String clientId, URI redirectUri, String state, String challenge,
                                @Nullable String scope, String resource, Map<String, String> additionalParameters) {
        try {
            var request = new AuthorizationRequest.Builder(ResponseType.CODE, new ClientID(clientId))
                    .endpointURI(authorizationEndpoint)
                    .redirectionURI(redirectUri)
                    .state(new State(state))
                    .codeChallenge(CodeChallenge.parse(challenge), CodeChallengeMethod.S256)
                    .resources(URI.create(resource));
            if (scope != null && !scope.isBlank()) request.scope(Scope.parse(scope));
            additionalParameters.forEach((name, value) -> {
                if (!AUTHORIZATION_PARAMETERS.contains(name)) request.customParameter(name, value);
            });
            return request.build().toURI();
        } catch (ParseException invalid) {
            throw new IllegalArgumentException("Invalid PKCE code challenge", invalid);
        }
    }

    public Tokens exchangeCode(URI tokenEndpoint, Client client, String code, URI redirectUri, String verifier, String resource) {
        return tokens(tokenEndpoint, client,
                new AuthorizationCodeGrant(new AuthorizationCode(code), redirectUri, new CodeVerifier(verifier)), resource);
    }

    /** Refreshes an access token; {@code invalid_grant} means the User or administrator must connect again. */
    public Tokens refresh(URI tokenEndpoint, Client client, String refreshToken, String resource) {
        return tokens(tokenEndpoint, client, new RefreshTokenGrant(new RefreshToken(refreshToken)), resource);
    }

    /** RFC 7009 revocation, best effort: the local disconnect has already committed. */
    public void revoke(URI revocationEndpoint, Client client, String token) {
        try {
            var revocation = client.method() == McpTokenEndpointAuthMethod.NONE
                    ? new TokenRevocationRequest(revocationEndpoint, new ClientID(client.clientId()), new TypelessToken(token))
                    : new TokenRevocationRequest(revocationEndpoint, clientAuthentication(client), new TypelessToken(token));
            send(revocation.toHTTPRequest(), false);
        } catch (RuntimeException ignored) {
            // Remote revocation cannot restore local authority.
        }
    }

    /**
     * RFC 7592 client deletion at {@code registration_client_uri} with the registration access token. Returns the
     * HTTP status; transport failures throw {@link McpException}. The caller has already deleted the client locally.
     */
    public int deregister(URI registrationClientUri, String registrationAccessToken) {
        return send(new ClientDeleteRequest(registrationClientUri, new BearerAccessToken(registrationAccessToken)).toHTTPRequest(),
                false).status();
    }

    /** Canonical MCP server URI for the RFC 8707 {@code resource} parameter. */
    public static String canonicalResource(String url) {
        URI uri = McpClients.endpoint(url);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + path;
    }

    /** Validates an authorization-server endpoint; only authorization endpoints may carry a query. */
    public static URI endpoint(String value, boolean allowQuery) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || (!allowQuery && uri.getRawQuery() != null) || value.length() > 2048) {
                throw McpException.oauthDiscoveryFailed("An authorization server endpoint is not an allowed URL.");
            }
            return uri;
        } catch (URISyntaxException invalid) {
            throw McpException.oauthDiscoveryFailed("An authorization server endpoint is not an allowed URL.");
        }
    }

    static Challenge challenge(List<String> headerValues) {
        for (String header : headerValues) {
            int bearer = header.toLowerCase(Locale.ROOT).indexOf("bearer");
            if (bearer < 0) continue;
            var parameters = new LinkedHashMap<String, String>();
            Matcher matcher = AUTH_PARAMETER.matcher(header.substring(bearer + "bearer".length()));
            while (matcher.find()) {
                String value = matcher.group(3) != null ? matcher.group(3).replaceAll("\\\\(.)", "$1") : matcher.group(2);
                parameters.putIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), value);
            }
            URI metadata = null;
            if (parameters.containsKey("resource_metadata")) {
                try {
                    metadata = endpoint(parameters.get("resource_metadata"), false);
                } catch (McpException ignored) {
                    // An unusable metadata address is treated as absent.
                }
            }
            return new Challenge(metadata, parameters.get("scope"));
        }
        return new Challenge(null, null);
    }

    static List<URI> protectedResourceMetadataUrls(URI server) {
        String origin = server.getScheme() + "://" + server.getRawAuthority();
        String path = trimTrailingSlash(server.getRawPath());
        return path.isEmpty() ? List.of(URI.create(origin + PROTECTED_RESOURCE))
                : List.of(URI.create(origin + PROTECTED_RESOURCE + path), URI.create(origin + PROTECTED_RESOURCE));
    }

    static List<URI> authorizationServerMetadataUrls(URI issuer) {
        String origin = issuer.getScheme() + "://" + issuer.getRawAuthority();
        String path = trimTrailingSlash(issuer.getRawPath());
        if (path.isEmpty()) {
            return List.of(URI.create(origin + "/.well-known/oauth-authorization-server"),
                    URI.create(origin + "/.well-known/openid-configuration"));
        }
        return List.of(URI.create(origin + "/.well-known/oauth-authorization-server" + path),
                URI.create(origin + "/.well-known/openid-configuration" + path),
                URI.create(origin + path + "/.well-known/openid-configuration"));
    }

    /**
     * The authorization server a metadata document describes, if it is usable: its issuer is the one listed (RFC 8414
     * §3.3, compared exactly with the callback's {@code iss}), it supports PKCE S256, and its endpoints are allowed.
     */
    static AuthorizationServer authorizationServer(String issuer, byte[] document) {
        AuthorizationServerMetadata metadata;
        JsonNode raw;
        try {
            raw = object(document);
            metadata = AuthorizationServerMetadata.parse(JSONObjectUtils.parse(new String(document, StandardCharsets.UTF_8)));
        } catch (ParseException | RuntimeException invalid) {
            throw McpException.oauthDiscoveryFailed("Authorization metadata is not valid.");
        }
        String metadataIssuer = metadata.getIssuer().getValue();
        if (metadataIssuer.length() > 2048 || !trimTrailingSlash(issuer).equals(trimTrailingSlash(metadataIssuer)))
            throw McpException.oauthDiscoveryFailed("The authorization server metadata names a different issuer.");
        var challenges = metadata.getCodeChallengeMethods();
        if (challenges == null || !challenges.contains(CodeChallengeMethod.S256))
            throw McpException.oauthDiscoveryFailed("The authorization server does not support PKCE S256.");
        URI authorization = metadata.getAuthorizationEndpointURI();
        URI token = metadata.getTokenEndpointURI();
        if (authorization == null || token == null)
            throw McpException.oauthDiscoveryFailed("Authorization metadata is missing an endpoint.");
        URI registration = metadata.getRegistrationEndpointURI();
        URI revocation = metadata.getRevocationEndpointURI();
        var methods = metadata.getTokenEndpointAuthMethods();
        return new AuthorizationServer(metadataIssuer,
                endpoint(authorization.toString(), true),
                endpoint(token.toString(), false),
                registration == null ? null : endpoint(registration.toString(), false),
                revocation == null ? null : endpoint(revocation.toString(), false),
                methods == null ? List.of() : methods.stream().map(ClientAuthenticationMethod::getValue).toList(),
                raw.path("client_id_metadata_document_supported").asBoolean(false),
                metadata.supportsAuthorizationResponseIssuerParam());
    }

    private Challenge unauthenticatedChallenge(URI server) {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"MemoryOS\",\"version\":\"1\"}}}";
        var probe = new HTTPRequest(HTTPRequest.Method.POST, server);
        probe.setEntityContentType(ContentType.APPLICATION_JSON);
        probe.setAccept("application/json, text/event-stream");
        probe.setBody(initialize);
        try {
            Response response = send(probe, false);
            return response.status() == 401 ? challenge(response.headers().getOrEmpty(HttpHeaders.WWW_AUTHENTICATE))
                    : new Challenge(null, null);
        } catch (McpException unreachable) {
            throw McpException.oauthDiscoveryFailed("The MCP server could not be reached for authorization discovery.");
        }
    }

    /** {@code alternative} is the origin when the document came from the root well-known URI, else null. */
    private ProtectedResource protectedResource(URI url, JsonNode document, URI server, @Nullable URI alternative) {
        String resource = text(document, "resource", 2048);
        try {
            String declared = canonicalResource(resource);
            if (!declared.equals(canonicalResource(server.toString()))
                    && (alternative == null || !declared.equals(canonicalResource(alternative.toString()))))
                throw McpException.oauthDiscoveryFailed("The protected-resource metadata describes a different resource.");
        } catch (IllegalArgumentException invalid) {
            throw McpException.oauthDiscoveryFailed("The protected-resource metadata describes a different resource.");
        }
        List<String> servers = strings(document, "authorization_servers", MAX_AUTHORIZATION_SERVERS);
        if (servers.isEmpty()) throw McpException.oauthDiscoveryFailed("The MCP server lists no authorization server.");
        return new ProtectedResource(url, resource, servers, strings(document, "scopes_supported", 256));
    }

    private @Nullable AuthorizationServer authorizationServer(String issuer) {
        URI issuerUri;
        try {
            issuerUri = endpoint(issuer, false);
        } catch (McpException invalid) {
            return null;
        }
        for (URI url : authorizationServerMetadataUrls(issuerUri)) {
            Response response = metadata(url);
            if (response.status() == 404) continue;
            return authorizationServer(issuer, response.body());
        }
        return null;
    }

    private Tokens tokens(URI tokenEndpoint, Client client, AuthorizationGrant grant, String resource) {
        var builder = client.method() == McpTokenEndpointAuthMethod.NONE
                ? new TokenRequest.Builder(tokenEndpoint, new ClientID(client.clientId()), grant)
                : new TokenRequest.Builder(tokenEndpoint, clientAuthentication(client), grant);
        String grantType = grant.getType().getValue();
        Response response;
        try {
            response = send(builder.resources(URI.create(resource)).build().toHTTPRequest(), true);
        } catch (McpException unavailable) {
            throw McpException.oauthTokenFailed();
        }
        if (response.status() == 400 || response.status() == 401) {
            String error = null;
            try {
                error = optionalText(object(response.body()), "error", 64);
            } catch (RuntimeException ignored) {
                // An unreadable error body is a token failure, not a reauthorization signal.
            }
            // RFC 6749 error codes are a closed vocabulary; anything else, and the description, stays out of logs.
            LOG.atWarn().addKeyValue("event", "mcp.oauth.token.rejected").addKeyValue("http_status", response.status())
                    .addKeyValue("error_code", error != null && OAUTH_ERROR_CODE.matcher(error).matches() ? error : "unreadable")
                    .addKeyValue("grant_type", grantType)
                    .log("Authorization server rejected the MCP OAuth token request");
            if ("invalid_grant".equals(error)) throw McpException.authorizationRequired();
            throw McpException.oauthTokenFailed();
        }
        if (response.status() != 200) {
            LOG.atWarn().addKeyValue("event", "mcp.oauth.token.rejected").addKeyValue("http_status", response.status())
                    .addKeyValue("grant_type", grantType)
                    .log("Authorization server rejected the MCP OAuth token request");
            throw McpException.oauthTokenFailed();
        }
        JsonNode raw;
        AccessTokenResponse parsed;
        try {
            raw = object(response.body());
            parsed = AccessTokenResponse.parse(nimbusObject(response.body()));
        } catch (ParseException | RuntimeException unreadable) {
            throw unusableToken(grantType, "response");
        }
        if (!(parsed.getTokens().getAccessToken() instanceof BearerAccessToken accessToken)) throw unusableToken(grantType, "token_type");
        // Nimbus also reads a string; MemoryOS keeps a lifetime only when it is a number within a year.
        Instant expiresAt = null;
        if (raw.has("expires_in")) {
            long seconds = raw.path("expires_in").asLong(-1);
            if (!raw.path("expires_in").isNumber() || seconds <= 0 || seconds > MAX_EXPIRES_IN_SECONDS)
                throw unusableToken(grantType, "expires_in");
            expiresAt = Instant.now().plusSeconds(seconds);
        }
        RefreshToken refreshToken = parsed.getTokens().getRefreshToken();
        // A grant without scopes comes back as "scope": "" from some servers (Linear); that is no scope, not a failure.
        JsonNode scope = raw.path("scope");
        try {
            return new Tokens(bounded(accessToken.getValue(), MAX_TOKEN_CHARACTERS),
                    refreshToken == null ? null : bounded(refreshToken.getValue(), MAX_TOKEN_CHARACTERS), expiresAt,
                    scope.isString() && scope.asString().isBlank() ? null : optionalText(raw, "scope", 4096));
        } catch (RuntimeException invalid) {
            throw unusableToken(grantType, "length");
        }
    }

    /** Names the rejected member of a 200 token response, never its value. */
    private static McpException unusableToken(String grantType, String reason) {
        LOG.atWarn().addKeyValue("event", "mcp.oauth.token.invalid").addKeyValue("grant_type", grantType)
                .addKeyValue("reason", reason).log("MCP OAuth token response is not a usable bearer token");
        return McpException.oauthTokenFailed();
    }

    private static ClientAuthentication clientAuthentication(Client client) {
        var id = new ClientID(client.clientId());
        var secret = new Secret(requireSecret(client));
        // RFC 6749 §2.3.1: Nimbus form-encodes both parts of the basic credentials before Base64.
        return client.method() == McpTokenEndpointAuthMethod.CLIENT_SECRET_POST ? new ClientSecretPost(id, secret)
                : new ClientSecretBasic(id, secret);
    }

    private @Nullable JsonNode optionalJson(URI url) {
        Response response = metadata(url);
        if (response.status() == 404) return null;
        try {
            return object(response.body());
        } catch (RuntimeException invalid) {
            throw McpException.oauthDiscoveryFailed("Authorization metadata is not a valid JSON object.");
        }
    }

    /** A metadata document; any answer but 200 or 404, a redirect included, ends discovery. */
    private Response metadata(URI url) {
        var request = new HTTPRequest(HTTPRequest.Method.GET, url);
        request.setAccept("application/json");
        Response response = send(request, true);
        if (response.status() != 200 && response.status() != 404)
            throw McpException.oauthDiscoveryFailed("Authorization metadata could not be read (HTTP " + response.status() + ").");
        return response;
    }

    private Response send(HTTPRequest request, boolean readBody) {
        try {
            var call = http.method(HttpMethod.valueOf(request.getMethod().name())).uri(request.getURI());
            request.getHeaderMap().forEach((name, values) -> call.header(name, values.toArray(String[]::new)));
            if (request.getBody() != null) call.body(request.getBody().getBytes(StandardCharsets.UTF_8));
            return call.exchange((sent, response) -> new Response(response.getStatusCode().value(), response.getHeaders(),
                    readBody ? response.getBody().readAllBytes() : new byte[0]), true);
        } catch (RestClientException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof HttpTimeoutException timeout) throw McpException.timeout(timeout);
            throw McpException.unavailable(failure);
        }
    }

    /** The strict reading every JSON answer gets before Nimbus reads it: one object, no duplicate member, nothing after. */
    private static JsonNode object(byte[] body) {
        JsonNode node = JSON.readTree(body);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("JSON object required");
        return node;
    }

    private static JSONObject nimbusObject(byte[] body) throws ParseException {
        object(body);
        return JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8));
    }

    private static String bounded(String value, int maximum) {
        if (value.isBlank() || value.length() > maximum) throw new IllegalArgumentException("Value out of bounds");
        return value;
    }

    private static String text(JsonNode node, String field, int maximum) {
        String value = optionalText(node, field, maximum);
        if (value == null) throw McpException.oauthDiscoveryFailed("Authorization metadata is missing " + field + ".");
        return value;
    }

    private static @Nullable String optionalText(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > maximum)
            throw McpException.oauthDiscoveryFailed("Authorization metadata has an invalid " + field + ".");
        return value.asString();
    }

    private static List<String> strings(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > maximum)
            throw McpException.oauthDiscoveryFailed("Authorization metadata has an invalid " + field + ".");
        var result = new ArrayList<String>();
        for (JsonNode item : value) {
            if (!item.isString() || item.asString().isBlank() || item.asString().length() > 2048)
                throw McpException.oauthDiscoveryFailed("Authorization metadata has an invalid " + field + ".");
            result.add(item.asString());
        }
        return List.copyOf(result);
    }

    private static String requireSecret(Client client) {
        if (client.clientSecret() == null) throw McpException.oauthTokenFailed();
        return client.clientSecret();
    }

    private static String trimTrailingSlash(@Nullable String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
