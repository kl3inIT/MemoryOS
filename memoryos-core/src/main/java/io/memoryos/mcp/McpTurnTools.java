package io.memoryos.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The MCP servers selected for one Chat turn, their enabled tools and one session per server. Credentials,
 * headers and endpoints stay inside this capability: Chat sees a model-facing name, a JSON schema and
 * {@link #call}. Sessions are opened lazily on the first call and closed together when the turn ends.
 */
public final class McpTurnTools implements AutoCloseable {
    /**
     * An enabled tool of this turn. {@code slug} is the bounded metric label; {@code serverName} is administrator text
     * for people to read, as is {@code title}, the tool's own display name when it gives one. {@code appResourceUri} is
     * its MCP Apps view (MEM-275); a tool not {@code modelVisible} is never offered to the model, and one not
     * {@code appVisible} cannot be called from a view.
     */
    public record Binding(UUID serverId, String slug, String serverName, String toolName, String modelName,
                          String description, String inputSchema, boolean readOnly, boolean approvalRequired,
                          @Nullable String title, @Nullable String appResourceUri, boolean modelVisible,
                          boolean appVisible) {}

    /** A server the User selected but cannot use yet, so the turn offers a connect action instead of tools. */
    public record Unavailable(UUID serverId, String serverName, Reason reason) {
        public enum Reason { NOT_CONNECTED, REAUTH_REQUIRED, NO_TOOLS }
    }

    /**
     * What a tool call produced. {@code error} is the server's own {@code isError}; {@code result} is the whole result
     * as JSON, for the tool's view.
     */
    public record Outcome(String text, boolean error, String result) {
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

    /** The person's answer to a call of a tool that requires approval. */
    public enum Decision { APPROVED, DECLINED, NO_DECISION }

    /**
     * Asks the person whether a call may run and waits for the answer; the turn that owns these tools supplies it.
     * {@code arguments} is the call's JSON as the model sent it.
     */
    @FunctionalInterface
    public interface Approvals {
        Decision decide(Binding binding, String callId, String arguments);
    }

    private final McpClients clients;
    private final Map<UUID, Target> targets;
    private final List<Binding> bindings;
    private final List<Unavailable> unavailable;
    private final Set<String> alwaysAllowable;
    private final Map<UUID, McpSession> sessions = new LinkedHashMap<>();
    private boolean closed;
    /** Until a turn with a person to ask supplies one, a call that requires approval is declined. */
    private volatile Approvals approvals = (binding, callId, arguments) -> Decision.DECLINED;

    record Target(UUID serverId, String url, Map<String, String> headers) {
        @Override public @NonNull String toString() { return "McpTurnTarget[redacted]"; }
    }

    McpTurnTools(McpClients clients, Map<UUID, Target> targets, List<Binding> bindings, List<Unavailable> unavailable,
                 Set<String> alwaysAllowable) {
        this.clients = clients;
        this.targets = Map.copyOf(targets);
        this.bindings = List.copyOf(bindings);
        this.unavailable = List.copyOf(unavailable);
        this.alwaysAllowable = Set.copyOf(alwaysAllowable);
    }

    public List<Binding> bindings() { return bindings; }

    public List<Unavailable> unavailable() { return unavailable; }

    /**
     * Whether the person deciding may answer this call with "always allow" (MEM-267): they manage MCP servers and the
     * tool asks first without being destructive, so the answer can set it to run without asking.
     */
    public boolean alwaysAllowable(Binding binding) { return alwaysAllowable.contains(binding.modelName()); }

    /** Set once by the turn that owns these tools, before the model runs. */
    public void approvals(Approvals approvals) { this.approvals = approvals; }

    /** Whether a call of {@code binding}, which requires approval, may run. */
    public Decision decide(Binding binding, String callId, String arguments) {
        return approvals.decide(binding, callId, arguments);
    }

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
            return new Outcome(result.text(), result.error(), result.result());
        } catch (McpException failure) {
            // The session is not reused after a transport failure; the next call opens a fresh one.
            if (failure.code().equals("MCP_TIMEOUT") || failure.code().equals("MCP_UNAVAILABLE")) discard(binding.serverId());
            throw new CallFailure(reason(failure));
        }
    }

    /** A tool of this turn by the server's own name for it, for a request of an MCP Apps view. */
    Optional<Binding> binding(UUID serverId, String toolName) {
        return bindings.stream().filter(b -> b.serverId().equals(serverId) && b.toolName().equals(toolName)).findFirst();
    }

    /**
     * Runs a request of an MCP Apps view on {@code serverId}'s session. Unlike {@link #call}, failures stay
     * {@link McpException}s, which the view host reports by code.
     */
    synchronized <T> T forView(UUID serverId, Duration deadline, Function<McpSession, T> request) {
        if (closed) throw McpException.unavailable(new IllegalStateException("closed"));
        var target = targets.get(serverId);
        if (target == null) throw McpException.notFound();
        var session = sessions.get(serverId);
        if (session == null) {
            session = clients.open(target.url(), target.headers(), deadline);
            sessions.put(serverId, session);
        }
        return request.apply(session);
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
