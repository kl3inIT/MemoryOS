package io.memoryos.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * A server serves the views its tools name through {@code resources/read} (SEP-1865), yet Canva declares only
 * {@code tools}, and the SDK refuses resource requests a server has not declared. Claude, ChatGPT and
 * assistant-ui read a named view regardless, so the session treats every server as serving resources. MemoryOS
 * reads resources only for a view a tool named ({@link McpAppHost}); a server without them answers with an error.
 */
final class McpViewResourcesTransport implements McpClientTransport {
    private final McpClientTransport delegate;

    McpViewResourcesTransport(McpClientTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(handler);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        delegate.setExceptionHandler(handler);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return delegate.sendMessage(message);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unmarshalFrom(Object data, TypeRef<T> type) {
        T value = delegate.unmarshalFrom(data, type);
        if (!(value instanceof McpSchema.InitializeResult result) || result.capabilities() == null
                || result.capabilities().resources() != null) return value;
        return (T) new McpSchema.InitializeResult(result.protocolVersion(),
                result.capabilities().mutate().resources(false, false).build(), result.serverInfo(),
                result.instructions(), result.meta());
    }
}
