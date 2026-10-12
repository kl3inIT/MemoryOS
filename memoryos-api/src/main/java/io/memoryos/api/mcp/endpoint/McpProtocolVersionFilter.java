package io.memoryos.api.mcp.endpoint;

import io.memoryos.mcp.McpEndpointProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MEM-114: the 2025-11-25 transport requires a server to answer an unsupported {@code MCP-Protocol-Version} with 400,
 * which Spring AI 2.0.1 does not check. ChatGPT opens with a 2026-07-28 {@code server/discover}; a 400 whose body is not
 * a modern JSON-RPC error makes such a dual-era client fall back to {@code initialize}, which the SDK speaks.
 *
 * <p>A plain servlet filter, so it runs after Spring Security and an anonymous probe still receives the 401 that starts
 * OAuth. Remove it once the Java SDK and Spring AI speak 2026-07-28 (java-sdk #1011).
 */
@Component
class McpProtocolVersionFilter extends OncePerRequestFilter {
    /** What MCP Java SDK 2.0.1 negotiates. */
    static final Set<String> SUPPORTED = Set.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && McpEndpointProperties.PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String version = request.getHeader("MCP-Protocol-Version");
        if (version == null || SUPPORTED.contains(version) || "initialize".equals(request.getHeader("Mcp-Method"))) {
            chain.doFilter(request, response);
            return;
        }
        String shown = version.matches("[0-9-]{1,20}") ? version : "unrecognized";
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // -32000, as the TypeScript SDK sends; -32601, -32020 or -32022 would mark this server as a modern one.
        response.getWriter().write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Bad Request: "
                + "Unsupported protocol version: " + shown + " (supported versions: 2025-11-25, 2025-06-18, "
                + "2025-03-26, 2024-11-05)\"},\"id\":null}");
    }
}
