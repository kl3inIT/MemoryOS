package io.memoryos.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * An MCP Apps view (SEP-1865): the HTML document of a {@code ui://} resource and the {@code _meta.ui} its server gives
 * with it, whose {@code csp} the renderer enforces inside the view.
 */
public record McpAppResource(String uri, String html, Map<String, Object> meta) {
    /** The MIME type of an MCP Apps view. */
    public static final String MIME_TYPE = "text/html;profile=mcp-app";
    /** The OpenAI Apps SDK's MIME type for the same kind of template. */
    static final String SKYBRIDGE_MIME_TYPE = "text/html+skybridge";
    /** The largest view MemoryOS renders. */
    static final int MAX_BYTES = 2 * 1024 * 1024;

    public McpAppResource {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(html, "html");
        // JSON metadata may hold null members, which Map.copyOf refuses.
        meta = meta == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    @Override public @NonNull String toString() { return "McpAppResource[" + uri + "]"; }
}
