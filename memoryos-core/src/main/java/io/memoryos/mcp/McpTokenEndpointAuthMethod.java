package io.memoryos.mcp;

import java.util.Arrays;

/** RFC 7591 {@code token_endpoint_auth_method} values MemoryOS supports for MCP OAuth clients. */
public enum McpTokenEndpointAuthMethod {
    NONE("none"),
    CLIENT_SECRET_BASIC("client_secret_basic"),
    CLIENT_SECRET_POST("client_secret_post");

    private final String wireValue;

    McpTokenEndpointAuthMethod(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static McpTokenEndpointAuthMethod fromWire(String value) {
        return Arrays.stream(values()).filter(method -> method.wireValue.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported token endpoint authentication method"));
    }
}
