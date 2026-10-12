package io.memoryos.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.mcp.McpException;
import org.junit.jupiter.api.Test;

class McpReturnPathTest {

    @Test
    void acceptsOnlyRelativeMemoryOsPages() {
        assertEquals("/chat/0f8f9c2e-1f3a-4c5d-8e7b-2a1b3c4d5e6f",
                McpReturnPath.validate("/chat/0f8f9c2e-1f3a-4c5d-8e7b-2a1b3c4d5e6f"));
        assertEquals("/projects/abc123", McpReturnPath.validate("/projects/abc123"));
        assertEquals("/admin/mcp", McpReturnPath.validate("/admin/mcp"));
        assertEquals("/settings/connections", McpReturnPath.validate("/settings/connections"));
        assertEquals("/", McpReturnPath.validate("/"));
        assertEquals("/", McpReturnPath.validate(null));
        assertEquals("/", McpReturnPath.validate("  "));
    }

    @Test
    void rejectsAnythingThatCouldLeaveMemoryOs() {
        for (String rejected : new String[] {"https://evil.example", "//evil.example", "/\\evil.example", "chat/1",
                "/chat/1?next=https://evil.example", "/chat/1#f", "/admin/users", "/chat/", "/chat/../admin", "/settings/general",
                "/chat/" + "x".repeat(65)}) {
            assertEquals("MCP_INVALID",
                    assertThrows(McpException.class, () -> McpReturnPath.validate(rejected), rejected).code());
        }
    }
}
