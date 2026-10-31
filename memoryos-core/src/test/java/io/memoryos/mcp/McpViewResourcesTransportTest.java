package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpViewResourcesTransportTest {
    private static final McpSchema.Implementation SERVER = McpSchema.Implementation.builder("canva", "1").build();

    @Test
    void aServerThatDeclaresOnlyToolsStillServesTheViewsItsToolsName() {
        var declared = new McpSchema.InitializeResult("2025-11-25",
                McpSchema.ServerCapabilities.builder().tools(false)
                        .extensions(Map.of("io.modelcontextprotocol/ui", Map.of())).build(),
                SERVER, "Use the design tools.", Map.of("source", "canva"));

        McpSchema.InitializeResult result = new McpViewResourcesTransport(returning(declared))
                .unmarshalFrom(Map.of(), new TypeRef<>() {});

        assertNotNull(result.capabilities().resources());
        assertEquals(declared.capabilities().tools(), result.capabilities().tools());
        assertEquals(declared.capabilities().extensions(), result.capabilities().extensions());
        assertEquals(SERVER, result.serverInfo());
        assertEquals("Use the design tools.", result.instructions());
        assertEquals(Map.of("source", "canva"), result.meta());
    }

    @Test
    void declaredResourcesAndOtherMessagesPassUnchanged() {
        var declared = McpSchema.InitializeResult.builder("2025-11-25",
                McpSchema.ServerCapabilities.builder().tools(false).resources(true, true).build(), SERVER).build();
        var listed = McpSchema.ListToolsResult.builder(List.of()).build();

        assertSame(declared, new McpViewResourcesTransport(returning(declared))
                .unmarshalFrom(Map.of(), new TypeRef<McpSchema.InitializeResult>() {}));
        assertSame(listed, new McpViewResourcesTransport(returning(listed))
                .unmarshalFrom(Map.of(), new TypeRef<McpSchema.ListToolsResult>() {}));
    }

    /** A transport whose every result reads back as {@code value}. */
    private static McpClientTransport returning(Object value) {
        return new McpClientTransport() {
            @Override
            public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.empty();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T unmarshalFrom(Object data, TypeRef<T> type) {
                return (T) value;
            }
        };
    }
}
