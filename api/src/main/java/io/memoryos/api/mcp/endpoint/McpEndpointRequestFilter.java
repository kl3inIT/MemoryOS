package io.memoryos.api.mcp.endpoint;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.mcp.McpEndpointActivity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * MEM-114: bounds every request to the MCP endpoint. A body over {@link McpEndpointLimits#maxRequestBytes()} is refused
 * with 413, whether it declares its length or arrives chunked. Each {@code tools/call} spends one token from the caller's
 * bucket (person and client) and one from the endpoint's; an empty bucket answers 429 with {@code Retry-After}.
 * Discovery, {@code initialize} and listing cost nothing, because ChatGPT repeats them before every call.
 *
 * <p>The transport refuses a request whose {@code Accept} lacks either {@code application/json} or
 * {@code text/event-stream}; as Onyx does, a client that sends less is given both rather than a 400.
 *
 * <p>Runs inside the endpoint's security chain after bearer authentication, so the caller is known.
 */
public final class McpEndpointRequestFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpEndpointRequestFilter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String ACCEPT = "application/json, text/event-stream";

    private final McpEndpointLimits limits;
    private final McpEndpointActivity activity;
    private final Bucket endpoint;
    private final Cache<String, Bucket> callers = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(15)).build();

    public McpEndpointRequestFilter(McpEndpointLimits limits, McpEndpointActivity activity) {
        this.limits = limits;
        this.activity = activity;
        this.endpoint = bucket(limits.globalCallsPerMinute());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int max = limits.maxRequestBytes();
        if (request.getContentLengthLong() > max) {
            refuse(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "MCP_REQUEST_TOO_LARGE", "Request Too Large");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            refuse(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "MCP_REQUEST_TOO_LARGE", "Request Too Large");
            return;
        }
        String caller = caller();
        String tool = toolCall(body);
        if (caller != null && tool != null) {
            int perCaller = limits.callerCallsPerMinute();
            var probe = callers.get(caller, ignored -> bucket(perCaller)).tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                recordLimited(tool);
                limited(response, probe, perCaller, "MCP_CALLER_RATE_LIMITED", "caller");
                return;
            }
            var shared = endpoint.tryConsumeAndReturnRemaining(1);
            if (!shared.isConsumed()) {
                recordLimited(tool);
                limited(response, shared, limits.globalCallsPerMinute(), "MCP_ENDPOINT_RATE_LIMITED", "endpoint");
                return;
            }
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    /** The person and the client they call through, so one person in two clients has two buckets. */
    private static @Nullable String caller() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof ActorAuthenticationToken token)) return null;
        String client = token.jwt().map(jwt -> jwt.getClaimAsString("azp")).orElse("unknown-client");
        return token.getPrincipal().actorId().value() + "\u001f" + client;
    }

    /** The tool a {@code tools/call} names, or null for any other request. */
    private static @Nullable String toolCall(byte[] body) {
        try {
            var request = JSON.readTree(body);
            if (!"tools/call".equals(request.path("method").asString(""))) return null;
            String tool = request.path("params").path("name").asString("");
            return tool.isBlank() ? "unknown" : tool;
        } catch (JacksonException malformed) {
            return null; // The transport answers a malformed body itself.
        }
    }

    private void recordLimited(String tool) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof ActorAuthenticationToken token) {
            activity.record(token.getPrincipal().actorId(), McpEndpointTools.client(), tool,
                    McpEndpointActivity.Outcome.RATE_LIMITED);
        }
    }

    private static Bucket bucket(int perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, WINDOW).build())
                .build();
    }

    private static void limited(HttpServletResponse response, ConsumptionProbe probe, int limit, String code, String scope)
            throws IOException {
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        LOGGER.atInfo().addKeyValue("event", "mcp_endpoint.rate_limited").addKeyValue("scope", scope)
                .log("MCP endpoint call refused by its rate limit");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setHeader("RateLimit-Limit", String.valueOf(limit));
        response.setHeader("RateLimit-Remaining", "0");
        refuse(response, 429, code, "Too Many Requests");
    }

    private static void refuse(HttpServletResponse response, int status, String code, String title) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\"}");
    }

    /** Replays the body this filter already read, for the transport behind it. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private final boolean acceptsBoth;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            String accept = request.getHeader(HttpHeaders.ACCEPT);
            this.acceptsBoth = accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)
                    && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        }

        @Override
        public @Nullable String getHeader(String name) {
            return !acceptsBoth && HttpHeaders.ACCEPT.equalsIgnoreCase(name) ? ACCEPT : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return !acceptsBoth && HttpHeaders.ACCEPT.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(ACCEPT)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            if (acceptsBoth) return super.getHeaderNames();
            var names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.add(HttpHeaders.ACCEPT);
            return Collections.enumeration(names);
        }

        @Override
        public ServletInputStream getInputStream() {
            var source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return source.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return source.read(buffer, offset, length); }
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
