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
                new McpToolDescriptor("search", " Search ", "Finds files", schema, true, false, null, null)));
        assertEquals("Search", snapshot.getFirst().title());
        assertTrue(snapshot.getFirst().readOnly());
        assertEquals(Boolean.FALSE, McpServerRules.hint(snapshot.getFirst().annotations(), "destructiveHint"));
        assertNull(McpServerRules.hint(snapshot.getFirst().annotations(), "openWorldHint"));
        assertFalse(snapshot.getFirst().inputSchema().isBlank());

        var tool = new McpToolDescriptor("search", null, "", schema, null, null, null, null);
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(tool, tool)));
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(
                new McpToolDescriptor("search", null, "", Map.of("type", "string"), null, null, null, null))));
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(
                new McpToolDescriptor("search", null, "d".repeat(16385), schema, null, null, null, null))));
        assertCode("MCP_TOOL_SNAPSHOT_INVALID", () -> McpServerRules.snapshot(List.of(
                new McpToolDescriptor("search", null, "", Map.of("type", "object", "description", "s".repeat(40000)), null, null, null, null))));
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
