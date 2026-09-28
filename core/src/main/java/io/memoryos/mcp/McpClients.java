package io.memoryos.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Opens Streamable HTTP MCP sessions against a configured endpoint. The endpoint policy follows the accepted
 * provider policy: HTTP(S) including internal hosts, no URL credentials, query or fragment. Callers supply the
 * resolved request headers for exactly one credential; sessions are never shared between credentials.
 */
@Component
public final class McpClients {
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$");
    /** Transport- and protocol-owned headers a configured template must not override. */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host", "content-length", "content-type", "accept", "connection", "transfer-encoding", "upgrade",
            "expect", "mcp-session-id", "mcp-protocol-version", "last-event-id");
    private static final McpSchema.Implementation CLIENT_INFO = McpSchema.Implementation.builder("MemoryOS", "1").build();

    private final Duration connectTimeout;
    private final Duration requestTimeout;

    public McpClients(
            @Value("${memoryos.mcp.connect-timeout:5s}") Duration connectTimeout,
            @Value("${memoryos.mcp.request-timeout:30s}") Duration requestTimeout) {
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    }

    /** Validates a server URL before it is stored or called. */
    public static URI endpoint(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException | NullPointerException invalid) {
            throw new IllegalArgumentException("MCP server URL is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("MCP server URL must be an http(s) URL without credentials, query or fragment");
        }
        return uri;
    }

    public static void requireHeaders(Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (name == null || !HEADER_NAME.matcher(name).matches()
                    || RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("MCP header name is not allowed");
            }
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.length() > 8192) {
                throw new IllegalArgumentException("MCP header value is not allowed");
            }
        });
    }

    /** Opens a session whose calls time out after {@code min(requestTimeout, deadline)}. */
    public McpSession open(String url, Map<String, String> headers, Duration deadline) {
        URI uri = endpoint(url);
        Map<String, String> requestHeaders = Map.copyOf(headers);
        requireHeaders(requestHeaders);
        Duration timeout = requirePositive(deadline, "deadline").compareTo(requestTimeout) < 0 ? deadline : requestTimeout;
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        var transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(path)
                .connectTimeout(connectTimeout.compareTo(timeout) < 0 ? connectTimeout : timeout)
                .openConnectionOnStartup(false)
                .resumableStreams(false)
                .httpRequestCustomizer((request, _, _, _, _) -> requestHeaders.forEach(request::setHeader))
                .build();
        var client = McpClient.sync(transport)
                .clientInfo(CLIENT_INFO)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
        return new McpSession(client);
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
