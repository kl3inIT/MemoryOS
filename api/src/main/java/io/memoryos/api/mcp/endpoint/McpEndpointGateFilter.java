package io.memoryos.api.mcp.endpoint;

import io.memoryos.mcp.McpEndpointProperties;
import io.memoryos.mcp.McpEndpointService;
import io.memoryos.shared.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MEM-114: the first filter of the endpoint's security chain. While the deployment has no endpoint URL or the Tenant's
 * switch is off, the endpoint and its metadata answer 404, so no client learns that an endpoint could exist; turning the
 * switch off refuses the very next request. A request from a browser page must come from the MemoryOS origin itself:
 * Claude and ChatGPT call from their servers and send no {@code Origin}.
 */
public final class McpEndpointGateFilter extends OncePerRequestFilter {
    private final McpEndpointService endpointSwitch;
    private final McpEndpointProperties endpoint;
    private final TenantId tenant;

    public McpEndpointGateFilter(McpEndpointService endpointSwitch, McpEndpointProperties endpoint, TenantId tenant) {
        this.endpointSwitch = endpointSwitch;
        this.endpoint = endpoint;
        this.tenant = tenant;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!endpointSwitch.available(tenant)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !sameOrigin(origin)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean sameOrigin(String origin) {
        try {
            URI own = endpoint.origin().orElseThrow();
            URI given = URI.create(origin);
            return own.getScheme().equalsIgnoreCase(given.getScheme()) && own.getHost().equalsIgnoreCase(given.getHost())
                    && own.getPort() == given.getPort();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
