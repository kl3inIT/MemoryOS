package io.memoryos.mcp;

import java.net.URI;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The deployment's MCP OAuth redirect URI. It must be HTTPS, or HTTP on a loopback host for development, and end
 * in {@link #CALLBACK_PATH}. The Client ID Metadata Document is served from the same origin when that is HTTPS.
 */
@Component
public final class McpOAuthProperties {
    public static final String CALLBACK_PATH = "/login/oauth2/code/mcp";
    public static final String CLIENT_METADATA_PATH = "/mcp/oauth/client-metadata.json";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "[::1]", "localhost");

    private final String redirectUri;

    public McpOAuthProperties(@Value("${memoryos.mcp.redirect-uri:}") String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public URI redirectUri() {
        if (redirectUri.isBlank()) throw McpException.oauthNotConfigured();
        try {
            URI uri = URI.create(redirectUri);
            boolean secure = "https".equals(uri.getScheme()) && uri.getHost() != null;
            boolean loopback = "http".equals(uri.getScheme()) && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost());
            if ((!secure && !loopback) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !CALLBACK_PATH.equals(uri.getPath())) {
                throw McpException.oauthNotConfigured();
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw McpException.oauthNotConfigured();
        }
    }

    /** The CIMD {@code client_id}, or null when the redirect origin is not HTTPS (the specification requires HTTPS). */
    public @Nullable URI clientMetadataDocumentUrl() {
        URI redirect = redirectUri();
        return "https".equals(redirect.getScheme())
                ? URI.create(redirect.getScheme() + "://" + redirect.getRawAuthority() + CLIENT_METADATA_PATH) : null;
    }
}
