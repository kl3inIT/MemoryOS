package io.memoryos.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * One initialized MCP client for one credential, used by a single caller at a time and closed by its owner.
 * Failures cross this boundary as {@link McpException} without upstream messages.
 */
public final class McpSession implements AutoCloseable {
    static final int MAX_TOOLS = 512;
    static final int MAX_TOOL_PAGES = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpSyncClient client;
    private boolean initialized;

    McpSession(McpSyncClient client) {
        this.client = client;
    }

    public List<McpToolDescriptor> listTools() {
        return guarded(() -> {
            initialize();
            var tools = new ArrayList<McpToolDescriptor>();
            String cursor = null;
            for (int page = 0; page < MAX_TOOL_PAGES; page++) {
                McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
                for (McpSchema.Tool tool : result.tools()) {
                    if (tools.size() == MAX_TOOLS) throw McpException.toolListTooLarge();
                    tools.add(descriptor(tool));
                }
                cursor = result.nextCursor();
                if (cursor == null || cursor.isEmpty()) return List.copyOf(tools);
            }
            throw McpException.toolListTooLarge();
        });
    }

    public McpToolResult call(String toolName, Map<String, Object> arguments) {
        return guarded(() -> {
            initialize();
            var result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments, null));
            return new McpToolResult(text(result), Boolean.TRUE.equals(result.isError()));
        });
    }

    @Override
    public void close() {
        try {
            if (!client.closeGracefully()) client.close();
        } catch (RuntimeException ignored) {
            client.close();
        }
    }

    private void initialize() {
        if (!initialized) {
            client.initialize();
            initialized = true;
        }
    }

    private static McpToolDescriptor descriptor(McpSchema.Tool tool) {
        var annotations = tool.annotations();
        return new McpToolDescriptor(tool.name(), tool.title(), tool.description(), tool.inputSchema(),
                annotations == null ? null : annotations.readOnlyHint(),
                annotations == null ? null : annotations.destructiveHint(),
                annotations == null ? null : annotations.idempotentHint(),
                annotations == null ? null : annotations.openWorldHint());
    }

    /** Text blocks, embedded text resources and resource links in order; structured content only when no text exists. */
    static String text(McpSchema.CallToolResult result) {
        var parts = new ArrayList<String>();
        boolean hasText = false;
        for (McpSchema.Content content : result.content() == null ? List.<McpSchema.Content>of() : result.content()) {
            switch (content) {
                case McpSchema.TextContent text -> hasText |= add(parts, text.text());
                case McpSchema.EmbeddedResource embedded when embedded.resource() instanceof McpSchema.TextResourceContents resource ->
                        hasText |= add(parts, resource.text());
                case McpSchema.ResourceLink link -> hasText |= add(parts, "link: " + link.uri()
                        + (link.title() == null ? "" : " title: " + link.title())
                        + (link.description() == null ? "" : " description: " + link.description()));
                default -> add(parts, "[" + content.type() + " content omitted]");
            }
        }
        if (!hasText && result.structuredContent() != null) {
            parts.add(JSON.writeValueAsString(result.structuredContent()));
        }
        return String.join("\n\n", parts);
    }

    private static boolean add(List<String> parts, @Nullable String value) {
        if (value == null || value.isEmpty()) return false;
        parts.add(value);
        return true;
    }

    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (McpException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw classify(failure);
        }
    }

    static McpException classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpHttpClientTransportAuthorizationException) return McpException.authorizationRequired(failure);
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) return McpException.timeout(failure);
            if (cause.getCause() == cause) break;
        }
        return McpException.unavailable(failure);
    }
}
