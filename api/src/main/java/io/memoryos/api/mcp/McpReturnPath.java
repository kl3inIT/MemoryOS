package io.memoryos.api.mcp;

import io.memoryos.mcp.McpException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where a User's browser returns after connecting. Only relative application paths are accepted, so a callback
 * can never be turned into an open redirect.
 */
final class McpReturnPath {
    static final String DEFAULT = "/";
    private static final int MAX_LENGTH = 512;
    private static final Pattern ALLOWED = Pattern.compile("^/(chat/[A-Za-z0-9_-]{1,64}|projects/[A-Za-z0-9_-]{1,64}|admin/mcp)?$");

    private McpReturnPath() {}

    static String validate(@Nullable String value) {
        if (value == null || value.isBlank()) return DEFAULT;
        if (value.length() > MAX_LENGTH || !ALLOWED.matcher(value).matches())
            throw McpException.invalid("That return address is not a MemoryOS page.");
        return value;
    }
}
