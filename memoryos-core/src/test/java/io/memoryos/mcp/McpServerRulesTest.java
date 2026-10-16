package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class McpServerRulesTest {

    @Test
    void slugsAreShortLowercaseAndAlphanumeric() {
        assertEquals("drive2", McpServerRules.slug("drive2"));
        for (String invalid : new String[] {"", "Drive", "my_drive", "abcdefghijklmnopq", null}) {
            assertCode("MCP_INVALID", () -> McpServerRules.slug(invalid));
        }
    }

    @Test
    void headerTemplatesAcceptOnlyTheApiKeyPlaceholderOnApiKeyServers() {
        var template = Map.of("X-Api-Key", "{api_key}", "X-Team", "research");
        assertEquals(template, McpServerRules.headerTemplate(template, McpAuthType.API_TOKEN));
        assertEquals(Map.of("X-Team", "research"), McpServerRules.headerTemplate(Map.of("X-Team", "research"), McpAuthType.NONE));

        assertCode("MCP_INVALID", () -> McpServerRules.headerTemplate(Map.of("X-Team", "research"), McpAuthType.API_TOKEN));
        assertCode("MCP_INVALID", () -> McpServerRules.headerTemplate(Map.of("X-Api-Key", "{api_key}"), McpAuthType.NONE));
        assertCode("MCP_INVALID", () -> McpServerRules.headerTemplate(Map.of("X-Api-Key", "{api_key}", "X-User", "{email}"), McpAuthType.API_TOKEN));
        assertCode("MCP_INVALID", () -> McpServerRules.headerTemplate(Map.of("Mcp-Session-Id", "x"), McpAuthType.NONE));
        assertCode("MCP_INVALID", () -> McpServerRules.headerTemplate(Map.of("X-Team", "a", "x-team", "b"), McpAuthType.NONE));
        assertCode("MCP_INVALID", () -> McpServerRules.headerTemplate(Map.of("authorization", "Bearer x"), McpAuthType.OAUTH));
        assertEquals(Map.of("X-Team", "research"), McpServerRules.headerTemplate(Map.of("X-Team", "research"), McpAuthType.OAUTH));
    }

    @Test
    void apiKeyServersDefaultToBearerAuthorizationAndSubstituteTheKey() {
        assertEquals(Map.of("Authorization", "Bearer secret"), McpServerRules.resolveHeaders(null, McpAuthType.API_TOKEN, "secret"));
        assertEquals(Map.of("X-Api-Key", "secret"),
                McpServerRules.resolveHeaders(Map.of("X-Api-Key", "{api_key}"), McpAuthType.API_TOKEN, "secret"));
        assertEquals(Map.of(), McpServerRules.resolveHeaders(null, McpAuthType.NONE, null));
        assertCode("MCP_CREDENTIAL_REQUIRED", () -> McpServerRules.resolveHeaders(null, McpAuthType.API_TOKEN, null));
        assertCode("MCP_INVALID", () -> McpServerRules.apiKey("line\nbreak"));
    }

    @Test
    void oauthScopesAndParametersExcludeRequestOwnedValues() {
        assertEquals(List.of("https://www.googleapis.com/auth/drive.readonly"),
                McpServerRules.scopes(List.of("https://www.googleapis.com/auth/drive.readonly")));
        assertCode("MCP_INVALID", () -> McpServerRules.scopes(List.of("two words")));
        assertEquals(Map.of("hd", "tasco.vn"), McpServerRules.parameters(Map.of("hd", "tasco.vn")));
        assertCode("MCP_INVALID", () -> McpServerRules.parameters(Map.of("redirect_uri", "https://evil.example")));
    }

    @Test
    void modelToolNamesMustFitModelLimits() {
        assertEquals("mcp_drive_search_files", McpServerRules.modelToolName("drive", "search_files").orElseThrow());
        assertTrue(McpServerRules.modelToolName("drive", "files/search").isEmpty());
        assertTrue(McpServerRules.modelToolName("drive", "x".repeat(60)).isEmpty());
    }

    @Test
    void snapshotsKeepHintsAndRejectInvalidTools() {
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
        var snapshot = McpServerRules.snapshot(List.of(
                new McpToolDescriptor("search", " Search ", "Finds files", schema, true, false, null, null, null, true, true)));
        assertEquals("Search", snapshot.getFirst().title());
        assertTrue(snapshot.getFirst().readOnly());
        assertEquals(Boolean.FALSE, McpServerRules.hint(snapshot.getFirst().annotations(), "destructiveHint"));
        assertNull(McpServerRules.hint(snapshot.getFirst().annotations(), "openWorldHint"));
        assertFalse(snapshot.getFirst().inputSchema().isBlank());

        var tool = new McpToolDescriptor("search", null, "", schema, null, null, null, null, null, true, true);
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(tool, tool)));
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(
                new McpToolDescriptor("search", null, "", Map.of("type", "string"), null, null, null, null, null, true, true))));
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(
                new McpToolDescriptor("search", null, "d".repeat(16385), schema, null, null, null, null, null, true, true))));
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(
                new McpToolDescriptor("search", null, "", Map.of("type", "object", "description", "s".repeat(40000)), null, null, null, null, null, true, true))));
    }

    @Test
    void snapshotsKeepOnlyBoundedViewUrisAndAVisibleSide() {
        var schema = Map.<String, Object>of("type", "object");
        var snapshot = McpServerRules.snapshot(List.of(
                new McpToolDescriptor("picker", null, "", schema, null, null, null, null, "ui://canva/picker", true, true),
                new McpToolDescriptor("page", null, "", schema, null, null, null, null, "https://canva.com/view", true, true),
                new McpToolDescriptor("long", null, "", schema, null, null, null, null, "ui://" + "x".repeat(1020), true, true),
                new McpToolDescriptor("select", null, "", schema, null, null, null, null, null, false, true),
                new McpToolDescriptor("hidden", null, "", schema, null, null, null, null, null, false, false)));
        assertEquals("ui://canva/picker", snapshot.get(0).appResourceUri());
        assertNull(snapshot.get(1).appResourceUri());
        assertNull(snapshot.get(2).appResourceUri());
        assertFalse(snapshot.get(3).modelVisible());
        assertTrue(snapshot.get(3).appVisible());
        // Hidden from both sides is read as the specification's default, visible to both.
        assertTrue(snapshot.get(4).modelVisible() && snapshot.get(4).appVisible());
    }

    @Test
    void annotationsSetTheDefaultAndADestructiveToolNeverRunsUnasked() {
        assertEquals(McpToolRisk.READ, McpServerRules.risk(true, "{\"readOnlyHint\":true,\"destructiveHint\":true}"));
        assertEquals(McpToolRisk.DESTRUCTIVE, McpServerRules.risk(false, "{\"readOnlyHint\":false,\"destructiveHint\":true}"));
        // The specification's implicit destructive default is not applied: an unannotated tool writes.
        assertEquals(McpToolRisk.WRITE, McpServerRules.risk(false, "{}"));
        assertEquals(McpToolPolicy.ALWAYS, McpServerRules.defaultPolicy(McpToolRisk.READ, true));
        assertEquals(McpToolPolicy.ASK, McpServerRules.defaultPolicy(McpToolRisk.WRITE, true));
        assertEquals(McpToolPolicy.ASK, McpServerRules.defaultPolicy(McpToolRisk.DESTRUCTIVE, true));
        assertEquals(McpToolPolicy.OFF, McpServerRules.defaultPolicy(McpToolRisk.READ, false));
        assertFalse(McpServerRules.allowed(McpToolRisk.DESTRUCTIVE, McpToolPolicy.ALWAYS));
        assertTrue(McpServerRules.allowed(McpToolRisk.DESTRUCTIVE, McpToolPolicy.ASK));
        assertTrue(McpServerRules.allowed(McpToolRisk.WRITE, McpToolPolicy.ALWAYS));
    }

    @Test
    void storedJsonThatIsNotTheExpectedShapeFailsClosed() {
        assertCode("MCP_CREDENTIAL_UNREADABLE", () -> McpServerRules.stringMap("[]"));
        assertCode("MCP_CREDENTIAL_UNREADABLE", () -> McpServerRules.stringMap("{\"a\":1}"));
        assertCode("MCP_CREDENTIAL_UNREADABLE", () -> McpServerRules.stringList("{"));
    }

    private static void assertCode(String code, Executable action) {
        assertEquals(code, assertThrows(McpException.class, action).code());
    }
}
