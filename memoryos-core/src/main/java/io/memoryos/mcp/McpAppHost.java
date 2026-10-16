package io.memoryos.mcp;

import io.memoryos.shared.ActorId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The requests an MCP Apps view makes of its host (MEM-275), on the one server whose tool showed it. Each request
 * resolves the server as a Chat turn does, so it needs the same access and a usable credential, and a view's tool
 * call goes through the same policy as the model's: a tool that is off or hidden from views is refused, and one that
 * asks first runs only once the person approved this call.
 */
@Service
public class McpAppHost {
    private static final Logger LOG = LoggerFactory.getLogger(McpAppHost.class);
    /** Bounded again by the configured MCP request timeout. */
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    private final McpTurnService turns;

    public McpAppHost(McpTurnService turns) {
        this.turns = turns;
    }

    /** A {@code ui://} view of the server. */
    public McpAppResource view(ActorId actor, UUID serverId, String uri) {
        if (uri == null || !uri.startsWith("ui://")) throw McpException.appResourceInvalid();
        return on(actor, serverId, "read_view", session -> session.readApp(uri));
    }

    /**
     * Calls a tool of the server for its view and returns the whole {@code CallToolResult} as JSON. {@code approved}
     * is the person's answer to the confirmation a tool that asks first shows in the view's place.
     */
    public String callTool(ActorId actor, UUID serverId, String toolName, Map<String, Object> arguments, boolean approved) {
        try (var tools = turns.open(actor, List.of(serverId))) {
            available(tools, serverId);
            var binding = tools.binding(serverId, toolName).filter(McpTurnTools.Binding::appVisible)
                    .orElseThrow(McpException::appToolNotAllowed);
            if (binding.approvalRequired() && !approved) throw McpException.appApprovalRequired();
            return logged(serverId, "call_tool", () -> tools.forView(serverId, DEADLINE,
                    session -> session.call(binding.toolName(), arguments).result()));
        }
    }

    /** {@code resources/read} on the server. */
    public String readResource(ActorId actor, UUID serverId, String uri) {
        if (uri == null || uri.isBlank()) throw McpException.invalid("A resource URI is required.");
        return on(actor, serverId, "read_resource", session -> session.readResource(uri));
    }

    /** {@code resources/list} on the server. */
    public String listResources(ActorId actor, UUID serverId, @Nullable String cursor) {
        return on(actor, serverId, "list_resources", session -> session.listResources(cursor));
    }

    private <T> T on(ActorId actor, UUID serverId, String request, Function<McpSession, T> action) {
        try (var tools = turns.open(actor, List.of(serverId))) {
            available(tools, serverId);
            return logged(serverId, request, () -> tools.forView(serverId, DEADLINE, action));
        }
    }

    /** A server the person cannot use now: not connected, expired, or with every tool turned off. */
    private static void available(McpTurnTools tools, UUID serverId) {
        for (var unavailable : tools.unavailable()) {
            if (!unavailable.serverId().equals(serverId)) continue;
            if (unavailable.reason() == McpTurnTools.Unavailable.Reason.NO_TOOLS) throw McpException.notFound();
            throw McpException.authorizationRequired();
        }
        if (tools.bindings().stream().noneMatch(binding -> binding.serverId().equals(serverId))) throw McpException.notFound();
    }

    private static <T> T logged(UUID serverId, String request, Supplier<T> action) {
        try {
            return action.get();
        } catch (McpException failure) {
            LOG.atWarn().addKeyValue("event", "mcp.app." + request + ".failed").addKeyValue("mcp_server_id", serverId)
                    .addKeyValue("error_type", failure.getClass().getName()).addKeyValue("error_code", failure.code())
                    .log("MCP Apps view request failed");
            throw failure;
        }
    }
}
