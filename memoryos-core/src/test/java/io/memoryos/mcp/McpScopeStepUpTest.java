package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * MEM-227: a server answering {@code 403 insufficient_scope} asks the person to connect again, and the scope it named is
 * handed to the connection so that connecting again asks for it (MCP authorization, step-up).
 */
class McpScopeStepUpTest {
    private static final UUID SERVER = UUID.randomUUID();

    @Test
    void a403InsufficientScopeNamesItsScopeAndOtherDenialsDoNot() {
        var stepUp = McpSession.classify(denied(403, "Bearer error=\"insufficient_scope\", scope=\"files:read files:write\""));
        assertEquals("MCP_AUTHORIZATION_REQUIRED", stepUp.code());
        assertEquals("files:read files:write", stepUp.requiredScope());

        // A step-up without a usable scope still asks the person to connect again, with nothing more to ask for.
        assertEquals("", McpSession.classify(denied(403, "Bearer error=\"insufficient_scope\"")).requiredScope());
        assertEquals("", McpSession.classify(denied(403, "Bearer error=\"insufficient_scope\", scope=\"a\\\"b\"")).requiredScope());

        var expired = McpSession.classify(denied(401, "Bearer error=\"invalid_token\""));
        assertEquals("MCP_AUTHORIZATION_REQUIRED", expired.code());
        assertNull(expired.requiredScope());
        assertNull(McpSession.classify(denied(403, "Bearer error=\"invalid_token\"")).requiredScope());
    }

    @Test
    void aToolCallAndAViewRequestHandTheScopeToTheConnection() {
        var clients = mock(McpClients.class);
        var session = mock(McpSession.class);
        when(clients.open(any(), any(), any())).thenReturn(session);
        when(session.call(eq("export"), anyMap())).thenThrow(McpSession.classify(
                denied(403, "Bearer error=\"insufficient_scope\", scope=\"files:write\"")));
        var recorded = new CopyOnWriteArrayList<String>();
        var binding = new McpTurnTools.Binding(SERVER, "drive", "Drive", "export", "mcp_drive_export", "export",
                "{\"type\":\"object\"}", true, false, null, null, true, true);
        try (var tools = new McpTurnTools(clients,
                Map.of(SERVER, new McpTurnTools.Target(SERVER, "https://drive.example/mcp", Map.of(), true, recorded::add)),
                List.of(binding), List.of(), Set.of())) {
            var failure = assertThrows(McpTurnTools.CallFailure.class,
                    () -> tools.call(binding, Map.of(), Instant.now().plusSeconds(30)));
            assertEquals(McpTurnTools.CallFailure.Reason.INSUFFICIENT_SCOPE, failure.reason());
            assertTrue(failure.shared());

            assertThrows(McpException.class, () -> tools.forView(SERVER, Duration.ofSeconds(5),
                    opened -> opened.call("export", Map.of())));
        }
        assertEquals(List.of("files:write", "files:write"), recorded);
    }

    private static McpHttpClientTransportAuthorizationException denied(int status, String challenge) {
        var headers = HttpHeaders.of(Map.of("WWW-Authenticate", List.of(challenge)), (name, value) -> true);
        HttpResponse.ResponseInfo response = new HttpResponse.ResponseInfo() {
            @Override public int statusCode() { return status; }
            @Override public HttpHeaders headers() { return headers; }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        var request = new HttpRequestSnapshot(URI.create("https://drive.example/mcp"), "POST",
                HttpHeaders.of(Map.of(), (name, value) -> true));
        return new McpHttpClientTransportAuthorizationException("denied", request, response);
    }
}
