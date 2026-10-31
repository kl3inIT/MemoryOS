package io.memoryos.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
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
            return new McpToolResult(text(result), Boolean.TRUE.equals(result.isError()), JSON.writeValueAsString(result));
        });
    }

    /**
     * Reads an MCP Apps view (MEM-275). The {@code _meta.ui} on the read content wins; without it the matching entry of
     * {@code resources/list} gives it, as the MCP Apps specification allows the server to declare it in either place.
     */
    public McpAppResource readApp(String uri) {
        return guarded(() -> {
            initialize();
            var read = client.readResource(new McpSchema.ReadResourceRequest(uri, null));
            var content = read.contents().stream()
                    .filter(McpSchema.TextResourceContents.class::isInstance).map(McpSchema.TextResourceContents.class::cast)
                    .filter(text -> uri.equals(text.uri())).findFirst()
                    .orElseThrow(McpException::appResourceInvalid);
            String mimeType = content.mimeType() == null ? "" : content.mimeType().replace(" ", "");
            if (!mimeType.equalsIgnoreCase(McpAppResource.MIME_TYPE) && !mimeType.equalsIgnoreCase(McpAppResource.SKYBRIDGE_MIME_TYPE))
                throw McpException.appResourceInvalid();
            if (content.text().getBytes(StandardCharsets.UTF_8).length > McpAppResource.MAX_BYTES)
                throw McpException.appResourceInvalid();
            var meta = ui(content.meta());
            if (meta == null) meta = listedMeta(uri);
            return new McpAppResource(uri, content.text(), meta == null ? Map.of() : meta);
        });
    }

    /** {@code resources/read} for a view, as JSON. */
    public String readResource(String uri) {
        return guarded(() -> {
            initialize();
            return JSON.writeValueAsString(client.readResource(new McpSchema.ReadResourceRequest(uri, null)));
        });
    }

    /** One page of {@code resources/list} for a view, as JSON. */
    public String listResources(@Nullable String cursor) {
        return guarded(() -> {
            initialize();
            return JSON.writeValueAsString(cursor == null ? client.listResources() : client.listResources(cursor));
        });
    }

    private @Nullable Map<String, Object> listedMeta(String uri) {
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            var listed = cursor == null ? client.listResources() : client.listResources(cursor);
            for (var resource : listed.resources())
                if (uri.equals(resource.uri())) return ui(resource.meta());
            cursor = listed.nextCursor();
            if (cursor == null || cursor.isEmpty()) return null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> ui(@Nullable Map<String, Object> meta) {
        return meta != null && meta.get("ui") instanceof Map<?, ?> ui ? (Map<String, Object>) ui : null;
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
                annotations == null ? null : annotations.openWorldHint(),
                appResourceUri(tool.meta()), visibleTo(tool.meta(), "model"), visibleTo(tool.meta(), "app"));
    }

    /**
     * The tool's view: SEP-1865's {@code _meta.ui.resourceUri}, else the deprecated flat {@code ui/resourceUri}, else the
     * OpenAI Apps SDK's {@code openai/outputTemplate}. Canva declares both of the first and the last.
     */
    static @Nullable String appResourceUri(@Nullable Map<String, Object> meta) {
        if (meta == null) return null;
        var ui = ui(meta);
        if (ui != null && ui.get("resourceUri") instanceof String uri) return uri;
        if (meta.get("ui/resourceUri") instanceof String uri) return uri;
        return meta.get("openai/outputTemplate") instanceof String uri ? uri : null;
    }

    /** {@code _meta.ui.visibility} names who may call the tool; without it, both the model and the view may. */
    static boolean visibleTo(@Nullable Map<String, Object> meta, String side) {
        var ui = ui(meta);
        return ui == null || !(ui.get("visibility") instanceof List<?> visibility) || visibility.contains(side);
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

    /** A 401, or a 403; MEM-227: a 403 {@code insufficient_scope} names the scope the next authorization asks for. */
    private static McpException denied(McpHttpClientTransportAuthorizationException denied, Throwable failure) {
        var response = denied.getResponseInfo();
        String scope = response == null || response.statusCode() != 403 ? null
                : McpOAuthProtocol.insufficientScope(response.headers().allValues("WWW-Authenticate"));
        return scope == null ? McpException.authorizationRequired(failure) : McpException.insufficientScope(scope, failure);
    }

    static McpException classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpHttpClientTransportAuthorizationException denied) return denied(denied, failure);
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) return McpException.timeout(failure);
            if (cause.getCause() == cause) break;
        }
        return McpException.unavailable(failure);
    }
}
