package io.memoryos.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth protocol calls for MCP authorization (MCP {@code 2025-11-25} authorization, RFC 9728, RFC 8414, RFC 7591,
 * RFC 8707). Redirects are never followed, responses are bounded, and upstream bodies never cross this boundary.
 * Endpoints follow the MCP endpoint policy: HTTP(S) including internal hosts, without credentials or fragments.
 */
@Component
public final class McpOAuthProtocol {
    static final int MAX_RESPONSE_BYTES = 65536;
    private static final int MAX_AUTHORIZATION_SERVERS = 4;
    private static final int MAX_TOKEN_CHARACTERS = 16384;
    private static final long MAX_EXPIRES_IN_SECONDS = 366L * 24 * 3600;
    private static final String PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource";
    private static final Pattern AUTH_PARAMETER = Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[^\\s,]+)");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final HttpClient http;
    private final Duration requestTimeout;

    public McpOAuthProtocol(@Value("${memoryos.mcp.connect-timeout:5s}") Duration connectTimeout,
                            @Value("${memoryos.mcp.request-timeout:30s}") Duration requestTimeout) {
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
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
        var body = new LinkedHashMap<String, Object>();
        body.put("client_name", clientName);
        body.put("redirect_uris", List.of(redirectUri.toString()));
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        body.put("token_endpoint_auth_method", requested.wireValue());
        Response response;
        try {
            response = send(HttpRequest.newBuilder(server.registrationEndpoint()).timeout(requestTimeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), true);
        } catch (McpException unavailable) {
            throw McpException.oauthRegistrationFailed();
        }
        if (response.status() != 200 && response.status() != 201) throw McpException.oauthRegistrationFailed();
        try {
            JsonNode registered = object(response.body());
            String clientId = text(registered, "client_id", 2048);
            String secret = optionalText(registered, "client_secret", MAX_TOKEN_CHARACTERS);
            String method = optionalText(registered, "token_endpoint_auth_method", 64);
            McpTokenEndpointAuthMethod granted = method == null ? requested : McpTokenEndpointAuthMethod.fromWire(method);
            if ((granted == McpTokenEndpointAuthMethod.NONE) != (secret == null)) throw McpException.oauthRegistrationFailed();
            String clientUri = optionalText(registered, "registration_client_uri", 2048);
            return new Registration(new Client(clientId, secret, granted),
                    clientUri == null ? null : endpoint(clientUri, false),
                    optionalText(registered, "registration_access_token", MAX_TOKEN_CHARACTERS));
        } catch (RuntimeException invalid) {
            throw McpException.oauthRegistrationFailed();
        }
    }

    public URI authorizationUrl(URI authorizationEndpoint, String clientId, URI redirectUri, String state, String challenge,
                                @Nullable String scope, String resource, Map<String, String> additionalParameters) {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("response_type", "code");
        parameters.put("client_id", clientId);
        parameters.put("redirect_uri", redirectUri.toString());
        parameters.put("state", state);
        parameters.put("code_challenge", challenge);
        parameters.put("code_challenge_method", "S256");
        if (scope != null && !scope.isBlank()) parameters.put("scope", scope);
        parameters.put("resource", resource);
        additionalParameters.forEach(parameters::putIfAbsent);
        String query = form(parameters);
        String existing = authorizationEndpoint.getRawQuery();
        String base = authorizationEndpoint.toString();
        return URI.create(existing == null ? base + "?" + query : base + "&" + query);
    }

    public Tokens exchangeCode(URI tokenEndpoint, Client client, String code, URI redirectUri, String verifier, String resource) {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("grant_type", "authorization_code");
        parameters.put("code", code);
        parameters.put("redirect_uri", redirectUri.toString());
        parameters.put("code_verifier", verifier);
        parameters.put("resource", resource);
        return tokens(tokenEndpoint, client, parameters);
    }

    /** Refreshes an access token; {@code invalid_grant} means the User or administrator must connect again. */
    public Tokens refresh(URI tokenEndpoint, Client client, String refreshToken, String resource) {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("grant_type", "refresh_token");
        parameters.put("refresh_token", refreshToken);
        parameters.put("resource", resource);
        return tokens(tokenEndpoint, client, parameters);
    }

    /** RFC 7009 revocation, best effort: the local disconnect has already committed. */
    public void revoke(URI revocationEndpoint, Client client, String token) {
        try {
            var parameters = new LinkedHashMap<String, String>();
            parameters.put("token", token);
            send(tokenRequest(revocationEndpoint, client, parameters), false);
        } catch (RuntimeException ignored) {
            // Remote revocation cannot restore local authority.
        }
    }

    /**
     * RFC 7592 client deletion at {@code registration_client_uri} with the registration access token. Returns the
     * HTTP status; transport failures throw {@link McpException}. The caller has already deleted the client locally.
     */
    public int deregister(URI registrationClientUri, String registrationAccessToken) {
        return send(HttpRequest.newBuilder(registrationClientUri).timeout(requestTimeout)
                .header("Authorization", "Bearer " + registrationAccessToken).DELETE().build(), false).status();
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

    private Challenge unauthenticatedChallenge(URI server) {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"MemoryOS\",\"version\":\"1\"}}}";
        try {
            Response response = send(HttpRequest.newBuilder(server).timeout(requestTimeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(initialize)).build(), false);
            return response.status() == 401 ? challenge(response.headers().allValues("WWW-Authenticate")) : new Challenge(null, null);
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
            JsonNode metadata = optionalJson(url);
            if (metadata == null) continue;
            // RFC 8414 §3.3: the metadata issuer is authoritative; the callback iss is compared with it exactly.
            String metadataIssuer = text(metadata, "issuer", 2048);
            if (!trimTrailingSlash(issuer).equals(trimTrailingSlash(metadataIssuer)))
                throw McpException.oauthDiscoveryFailed("The authorization server metadata names a different issuer.");
            if (!strings(metadata, "code_challenge_methods_supported", 32).contains("S256"))
                throw McpException.oauthDiscoveryFailed("The authorization server does not support PKCE S256.");
            String registration = optionalText(metadata, "registration_endpoint", 2048);
            String revocation = optionalText(metadata, "revocation_endpoint", 2048);
            return new AuthorizationServer(metadataIssuer,
                    endpoint(text(metadata, "authorization_endpoint", 2048), true),
                    endpoint(text(metadata, "token_endpoint", 2048), false),
                    registration == null ? null : endpoint(registration, false),
                    revocation == null ? null : endpoint(revocation, false),
                    strings(metadata, "token_endpoint_auth_methods_supported", 32),
                    metadata.path("client_id_metadata_document_supported").asBoolean(false),
                    metadata.path("authorization_response_iss_parameter_supported").asBoolean(false));
        }
        return null;
    }

    private Tokens tokens(URI tokenEndpoint, Client client, Map<String, String> parameters) {
        Response response;
        try {
            response = send(tokenRequest(tokenEndpoint, client, parameters), true);
        } catch (McpException unavailable) {
            throw McpException.oauthTokenFailed();
        }
        if (response.status() == 400 || response.status() == 401) {
            try {
                if ("invalid_grant".equals(optionalText(object(response.body()), "error", 64)))
                    throw McpException.authorizationRequired();
            } catch (McpException required) {
                if ("MCP_AUTHORIZATION_REQUIRED".equals(required.code())) throw required;
            } catch (RuntimeException ignored) {
                // An unreadable error body is a token failure, not a reauthorization signal.
            }
            throw McpException.oauthTokenFailed();
        }
        if (response.status() != 200) throw McpException.oauthTokenFailed();
        try {
            JsonNode token = object(response.body());
            if (!"bearer".equalsIgnoreCase(text(token, "token_type", 32))) throw McpException.oauthTokenFailed();
            Instant expiresAt = null;
            if (token.has("expires_in")) {
                long seconds = token.path("expires_in").asLong(-1);
                if (!token.path("expires_in").isNumber() || seconds <= 0 || seconds > MAX_EXPIRES_IN_SECONDS)
                    throw McpException.oauthTokenFailed();
                expiresAt = Instant.now().plusSeconds(seconds);
            }
            return new Tokens(text(token, "access_token", MAX_TOKEN_CHARACTERS),
                    optionalText(token, "refresh_token", MAX_TOKEN_CHARACTERS), expiresAt, optionalText(token, "scope", 4096));
        } catch (RuntimeException invalid) {
            throw McpException.oauthTokenFailed();
        }
    }

    private HttpRequest tokenRequest(URI endpoint, Client client, Map<String, String> parameters) {
        var form = new LinkedHashMap<>(parameters);
        var request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json");
        switch (client.method()) {
            case NONE -> form.put("client_id", client.clientId());
            case CLIENT_SECRET_POST -> {
                form.put("client_id", client.clientId());
                form.put("client_secret", requireSecret(client));
            }
            case CLIENT_SECRET_BASIC -> {
                // RFC 6749 §2.3.1: form-encode both parts before Base64.
                String credentials = encode(client.clientId()) + ":" + encode(requireSecret(client));
                request.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return request.POST(HttpRequest.BodyPublishers.ofString(form(form))).build();
    }

    private @Nullable JsonNode optionalJson(URI url) {
        Response response = send(HttpRequest.newBuilder(url).timeout(requestTimeout).header("Accept", "application/json")
                .GET().build(), true);
        if (response.status() == 404) return null;
        if (response.status() != 200)
            throw McpException.oauthDiscoveryFailed("Authorization metadata could not be read (HTTP " + response.status() + ").");
        try {
            return object(response.body());
        } catch (RuntimeException invalid) {
            throw McpException.oauthDiscoveryFailed("Authorization metadata is not a valid JSON object.");
        }
    }

    private Response send(HttpRequest request, boolean readBody) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = readBody ? body.readNBytes(MAX_RESPONSE_BYTES + 1) : new byte[0];
                if (bytes.length > MAX_RESPONSE_BYTES) throw McpException.unavailable(new IOException("Response too large"));
                return new Response(response.statusCode(), response.headers(), bytes);
            }
        } catch (HttpTimeoutException timeout) {
            throw McpException.timeout(timeout);
        } catch (IOException failure) {
            throw McpException.unavailable(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw McpException.unavailable(interrupted);
        }
    }

    private static JsonNode object(byte[] body) {
        JsonNode node = JSON.readTree(body);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("JSON object required");
        return node;
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

    private static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
