package io.memoryos.mcp;

import io.memoryos.shared.ActorId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The MCP servers selected for one Chat turn, their enabled tools and one session per server. Credentials,
 * headers and endpoints stay inside this capability: Chat sees a model-facing name, a JSON schema and
 * {@link #call}. Sessions are opened lazily on the first call and closed together when the turn ends.
 */
public final class McpTurnTools implements AutoCloseable {
    /** A tool offered to the model for this turn. */
    /** {@code slug} is the bounded metric label; {@code serverName} is administrator text for people to read. */
    public record Binding(UUID serverId, String slug, String serverName, String toolName, String modelName,
                          String description, String inputSchema, boolean readOnly) {}

    /** A server the User selected but cannot use yet, so the turn offers a connect action instead of tools. */
    public record Unavailable(UUID serverId, String serverName, Reason reason) {
        public enum Reason { NOT_CONNECTED, REAUTH_REQUIRED, NO_TOOLS }
    }

    /** What a tool call produced. {@code error} is the server's own {@code isError}. */
    public record Outcome(String text, boolean error) {
        @Override public @NonNull String toString() { return "McpOutcome[redacted]"; }
    }

    /** Raised when a call cannot run; Chat maps the reason to a model-facing sentence with no upstream text. */
    public static final class CallFailure extends RuntimeException {
        private final Reason reason;

        public CallFailure(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        public enum Reason { AUTHORIZATION_REQUIRED, TIMEOUT, UNAVAILABLE, UNKNOWN_TOOL, INVALID_ARGUMENTS }

        public Reason reason() { return reason; }
    }

    private final McpClients clients;
    private final Map<UUID, Target> targets;
    private final List<Binding> bindings;
    private final List<Unavailable> unavailable;
    private final Map<UUID, McpSession> sessions = new LinkedHashMap<>();
    private boolean closed;

    record Target(UUID serverId, String url, Map<String, String> headers) {
        @Override public @NonNull String toString() { return "McpTurnTarget[redacted]"; }
    }

    McpTurnTools(McpClients clients, Map<UUID, Target> targets, List<Binding> bindings, List<Unavailable> unavailable) {
        this.clients = clients;
        this.targets = Map.copyOf(targets);
        this.bindings = List.copyOf(bindings);
        this.unavailable = List.copyOf(unavailable);
    }

    public List<Binding> bindings() { return bindings; }

    public List<Unavailable> unavailable() { return unavailable; }

    /**
     * Calls one tool with the turn's credential. {@code deadline} bounds this call together with the configured
     * request timeout; the caller has already checked that the turn is still active.
     */
    public synchronized Outcome call(Binding binding, Map<String, Object> arguments, Instant deadline) {
        if (closed) throw new CallFailure(CallFailure.Reason.UNAVAILABLE);
        if (!bindings.contains(binding)) throw new CallFailure(CallFailure.Reason.UNKNOWN_TOOL);
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new CallFailure(CallFailure.Reason.TIMEOUT);
        var session = session(binding.serverId(), remaining);
        try {
            var result = session.call(binding.toolName(), arguments);
            return new Outcome(result.text(), result.error());
        } catch (McpException failure) {
            // The session is not reused after a transport failure; the next call opens a fresh one.
            if (failure.code().equals("MCP_TIMEOUT") || failure.code().equals("MCP_UNAVAILABLE")) discard(binding.serverId());
            throw new CallFailure(reason(failure));
        }
    }

    private McpSession session(UUID serverId, Duration remaining) {
        var existing = sessions.get(serverId);
        if (existing != null) return existing;
        var target = targets.get(serverId);
        if (target == null) throw new CallFailure(CallFailure.Reason.UNKNOWN_TOOL);
        try {
            var opened = clients.open(target.url(), target.headers(), remaining);
            sessions.put(serverId, opened);
            return opened;
        } catch (McpException failure) {
            throw new CallFailure(reason(failure));
        }
    }

    private void discard(UUID serverId) {
        var session = sessions.remove(serverId);
        if (session != null) closeQuietly(session);
    }

    private static CallFailure.Reason reason(McpException failure) {
        return switch (failure.code()) {
            case "MCP_AUTHORIZATION_REQUIRED", "MCP_CREDENTIAL_REQUIRED", "MCP_CREDENTIAL_UNREADABLE" ->
                    CallFailure.Reason.AUTHORIZATION_REQUIRED;
            case "MCP_TIMEOUT" -> CallFailure.Reason.TIMEOUT;
            case "MCP_INVALID" -> CallFailure.Reason.INVALID_ARGUMENTS;
            default -> CallFailure.Reason.UNAVAILABLE;
        };
    }

    @Override public synchronized void close() {
        closed = true;
        var open = new ArrayList<>(sessions.values());
        sessions.clear();
        open.forEach(McpTurnTools::closeQuietly);
    }

    private static void closeQuietly(@Nullable McpSession session) {
        if (session == null) return;
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // A turn that is ending must not fail because a remote session could not be closed cleanly.
        }
    }
}
