package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class McpOAuthPropertiesTest {

    @Test
    void acceptsHttpsOrLoopbackCallbacksOnTheMcpPath() {
        assertEquals(URI.create("https://memoryos.tasco.vn/login/oauth2/code/mcp"),
                new McpOAuthProperties("https://memoryos.tasco.vn/login/oauth2/code/mcp").redirectUri());
        assertEquals(URI.create("http://127.0.0.1:8080/login/oauth2/code/mcp"),
                new McpOAuthProperties("http://127.0.0.1:8080/login/oauth2/code/mcp").redirectUri());
        assertEquals(URI.create("http://localhost/login/oauth2/code/mcp"),
                new McpOAuthProperties("http://localhost/login/oauth2/code/mcp").redirectUri());

        for (String invalid : new String[] {"", "http://memoryos.internal/login/oauth2/code/mcp",
                "https://memoryos.tasco.vn/login/oauth2/code/google-drive", "https://memoryos.tasco.vn/login/oauth2/code/mcp?x=1",
                "https://memoryos.tasco.vn/login/oauth2/code/mcp#f", "https://user@memoryos.tasco.vn/login/oauth2/code/mcp",
                "not a uri"}) {
            assertEquals("MCP_OAUTH_NOT_CONFIGURED",
                    assertThrows(McpException.class, () -> new McpOAuthProperties(invalid).redirectUri(), invalid).code());
        }
    }

    @Test
    void clientMetadataDocumentRequiresAnHttpsOrigin() {
        assertEquals(URI.create("https://memoryos.tasco.vn:8443/mcp/oauth/client-metadata.json"),
                new McpOAuthProperties("https://memoryos.tasco.vn:8443/login/oauth2/code/mcp").clientMetadataDocumentUrl());
        assertNull(new McpOAuthProperties("http://127.0.0.1:8080/login/oauth2/code/mcp").clientMetadataDocumentUrl());
    }
}
