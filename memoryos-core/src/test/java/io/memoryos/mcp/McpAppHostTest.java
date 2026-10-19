package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.shared.ActorId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** A view's requests go through the same policy as the model's calls, on the server that showed the view only. */
class McpAppHostTest {
    private static final UUID SERVER = UUID.randomUUID();
    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());

    private final McpTurnService turns = mock(McpTurnService.class);
    private final McpClients clients = mock(McpClients.class);
    private final McpSession session = mock(McpSession.class);
    private final McpAppHost host = new McpAppHost(turns);
    private List<McpTurnTools.Binding> bindings = List.of(
            binding("create-design", false, null, true, true),
            binding("select", false, null, false, true),
            binding("publish", true, null, true, true),
            binding("summarize", false, null, true, false));
    private List<McpTurnTools.Unavailable> unavailable = List.of();

    @BeforeEach
    void open() {
        when(clients.open(any(), any(), any())).thenReturn(session);
        when(turns.open(ACTOR, List.of(SERVER))).thenAnswer(ignored -> new McpTurnTools(clients,
                Map.of(SERVER, new McpTurnTools.Target(SERVER, "https://canva.example/mcp", Map.of(), false, null)),
                bindings, unavailable, Set.of()));
    }

    @Test
    void runsAToolTheViewMayCallIncludingOneHiddenFromTheModel() {
        when(session.call(eq("select"), anyMap())).thenReturn(new McpToolResult("ok", false, "{\"content\":[]}"));
        assertEquals("{\"content\":[]}", host.callTool(ACTOR, SERVER, "select", Map.of("id", 1), false));
    }

    @Test
    void aToolThatAsksFirstRunsOnlyOnceApproved() {
        assertCode("MCP_APP_APPROVAL_REQUIRED", () -> host.callTool(ACTOR, SERVER, "publish", Map.of(), false));
        verify(session, never()).call(any(), anyMap());
        when(session.call(eq("publish"), anyMap())).thenReturn(new McpToolResult("done", false, "{}"));
        assertEquals("{}", host.callTool(ACTOR, SERVER, "publish", Map.of(), true));
    }

    @Test
    void refusesAModelOnlyToolAndOneThatIsOffOrUnknown() {
        assertCode("MCP_APP_TOOL_NOT_ALLOWED", () -> host.callTool(ACTOR, SERVER, "summarize", Map.of(), true));
        // A tool that is off is not among the turn's tools.
        assertCode("MCP_APP_TOOL_NOT_ALLOWED", () -> host.callTool(ACTOR, SERVER, "delete-design", Map.of(), true));
        verify(session, never()).call(any(), anyMap());
    }

    @Test
    void readsOnlyUiViewsAndNeedsAUsableConnection() {
        assertCode("MCP_APP_RESOURCE_INVALID", () -> host.view(ACTOR, SERVER, "https://canva.example/view"));
        unavailable = List.of(new McpTurnTools.Unavailable(SERVER, "Canva", McpTurnTools.Unavailable.Reason.REAUTH_REQUIRED));
        bindings = List.of();
        assertCode("MCP_AUTHORIZATION_REQUIRED", () -> host.view(ACTOR, SERVER, "ui://canva/create-design"));
        unavailable = List.of();
        assertCode("MCP_NOT_FOUND", () -> host.view(ACTOR, SERVER, "ui://canva/create-design"));
    }

    @Test
    void readsAViewOfTheServer() {
        var view = new McpAppResource("ui://canva/create-design", "<html></html>", Map.of());
        when(session.readApp("ui://canva/create-design")).thenReturn(view);
        assertEquals(view, host.view(ACTOR, SERVER, "ui://canva/create-design"));
    }

    private static McpTurnTools.Binding binding(String name, boolean asks, String view, boolean model, boolean app) {
        return new McpTurnTools.Binding(SERVER, "canva", "Canva", name, "mcp_canva_" + name.replace('-', '_'),
                name, "{\"type\":\"object\"}", !asks, asks, null, view, model, app);
    }

    private static void assertCode(String code, Executable action) {
        assertEquals(code, assertThrows(McpException.class, action).code());
    }
}
