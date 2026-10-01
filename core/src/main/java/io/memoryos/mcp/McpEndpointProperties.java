package io.memoryos.mcp;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MEM-114: where this deployment serves its MCP endpoint, exactly the browser origin followed by {@link #PATH}. The URL
 * is the access token's audience and the protected resource Claude and ChatGPT are given, so it is one fixed string
 * rather than derived from a request. Unset, the deployment has no endpoint and the Tenant switch stays locked.
 */
@Component
public final class McpEndpointProperties {
    public static final String PATH = "/mcp";
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource" + PATH;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "[::1]", "localhost");

    private final @Nullable URI url;

    public McpEndpointProperties(@Value("${memoryos.mcp.endpoint.url:}") String url) {
        this.url = url.isBlank() ? null : validated(url);
    }

    /** A malformed URL fails startup instead of serving an endpoint no client can reach. */
    private static URI validated(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("memoryos.mcp.endpoint.url is not a URL", invalid);
        }
        boolean secure = "https".equals(uri.getScheme()) && uri.getHost() != null;
        boolean loopback = "http".equals(uri.getScheme()) && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost());
        if ((!secure && !loopback) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !PATH.equals(uri.getPath())) {
            throw new IllegalArgumentException(
                    "memoryos.mcp.endpoint.url must be an HTTPS (or loopback HTTP) origin followed by " + PATH);
        }
        return uri;
    }

    public boolean configured() {
        return url != null;
    }

    public Optional<URI> url() {
        return Optional.ofNullable(url);
    }

    /** The browser origin the endpoint shares, where a Document without a provider link opens. */
    public Optional<URI> origin() {
        return url().map(endpoint -> endpoint.resolve("/"));
    }
}
