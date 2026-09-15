package io.memoryos.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** Validation and conversion rules for administered MCP servers and their tool snapshots. */
final class McpServerRules {
    static final int MAX_SERVERS = 64;
    static final int MAX_GROUPS = 100;
    static final String API_KEY = "api_key";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int MAX_HEADERS = 32;
    private static final int MAX_SCOPES = 64;
    private static final int MAX_PARAMETERS = 32;
    private static final int MAX_API_KEY_CHARACTERS = 8192;
    private static final int MAX_TOOL_NAME = 128;
    private static final int MAX_TOOL_TITLE = 200;
    private static final int MAX_TOOL_DESCRIPTION = 16384;
    /** Compact JSON bound; V63 checks PostgreSQL's wider jsonb text form against 65536 bytes. */
    private static final int MAX_SCHEMA_BYTES = 32768;
    private static final Map<String, String> DEFAULT_API_TOKEN_HEADERS = Map.of("Authorization", "Bearer {api_key}");
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]{1,16}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern MODEL_TOOL_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    /** RFC 6749 scope-token. */
    private static final Pattern SCOPE = Pattern.compile("^[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,256}$");
    private static final Pattern PARAMETER_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");
    /** Parameters MemoryOS owns in the authorization request. */
    private static final Set<String> RESERVED_PARAMETERS = Set.of("response_type", "client_id", "redirect_uri", "state",
            "code_challenge", "code_challenge_method", "scope", "resource", "nonce", "iss");

    record ToolSnapshot(String name, @Nullable String title, String description, String inputSchema, String annotations,
                        boolean readOnly) {}

    private McpServerRules() {}

    static String slug(@Nullable String value) {
        if (value == null || !SLUG.matcher(value).matches())
            throw McpException.invalid("The slug must be 1 to 16 lowercase letters or digits.");
        return value;
    }

    static String text(@Nullable String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.strip().length() > maximum)
            throw McpException.invalid("The " + field + " must contain 1 to " + maximum + " characters.");
        return value.strip();
    }

    static @Nullable String optionalText(@Nullable String value, int maximum, String field) {
        return value == null || value.isBlank() ? null : text(value, maximum, field);
    }

    static String url(@Nullable String value) {
        String url = text(value, 2048, "URL");
        try {
            McpClients.endpoint(url);
        } catch (IllegalArgumentException invalid) {
            throw McpException.invalid("The MCP server URL must be an http(s) URL without credentials, query or fragment.");
        }
        return url;
    }

    /** Header template: API-token servers must place {@code {api_key}}; no other placeholder exists yet. */
    static Map<String, String> headerTemplate(@Nullable Map<String, String> headers, McpAuthType authType) {
        if (headers == null || headers.size() > MAX_HEADERS)
            throw McpException.invalid("Provide at most " + MAX_HEADERS + " headers.");
        var names = new HashSet<String>();
        boolean usesKey = false;
        for (var header : headers.entrySet()) {
            try {
                McpClients.requireHeaders(Map.of(header.getKey(), header.getValue()));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw McpException.invalid("A header name or value is not allowed.");
            }
            if (!names.add(header.getKey().toLowerCase(Locale.ROOT)))
                throw McpException.invalid("Each header can be configured once.");
            if (authType == McpAuthType.OAUTH && "authorization".equalsIgnoreCase(header.getKey()))
                throw McpException.invalid("OAuth servers send the Authorization header from their connection.");
            Matcher placeholders = PLACEHOLDER.matcher(header.getValue());
            while (placeholders.find()) {
                if (authType != McpAuthType.API_TOKEN || !API_KEY.equals(placeholders.group(1)))
                    throw McpException.invalid("Only API-key servers accept the {api_key} placeholder.");
                usesKey = true;
            }
        }
        if (authType == McpAuthType.API_TOKEN && !headers.isEmpty() && !usesKey)
            throw McpException.invalid("An API-key header template must use {api_key}.");
        return new LinkedHashMap<>(headers);
    }

    static Map<String, String> resolveHeaders(@Nullable Map<String, String> template, McpAuthType authType,
                                              @Nullable String apiKey) {
        Map<String, String> source = template == null || template.isEmpty()
                ? authType == McpAuthType.API_TOKEN ? DEFAULT_API_TOKEN_HEADERS : Map.of()
                : template;
        var resolved = new LinkedHashMap<String, String>();
        source.forEach((name, value) -> {
            if (value.contains("{" + API_KEY + "}")) {
                if (apiKey == null) throw McpException.credentialRequired();
                value = value.replace("{" + API_KEY + "}", apiKey);
            }
            resolved.put(name, value);
        });
        try {
            McpClients.requireHeaders(resolved);
        } catch (IllegalArgumentException invalid) {
            throw McpException.credentialUnreadable();
        }
        return resolved;
    }

    static String apiKey(@Nullable String value) {
        if (value == null || value.isBlank() || value.length() > MAX_API_KEY_CHARACTERS
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw McpException.invalid("The API key must contain 1 to " + MAX_API_KEY_CHARACTERS + " characters on one line.");
        return value;
    }

    static List<String> scopes(@Nullable List<String> scopes) {
        if (scopes == null || scopes.size() > MAX_SCOPES)
            throw McpException.invalid("Provide at most " + MAX_SCOPES + " OAuth scopes.");
        for (String scope : scopes) {
            if (scope == null || !SCOPE.matcher(scope).matches()) throw McpException.invalid("An OAuth scope is not valid.");
        }
        if (Set.copyOf(scopes).size() != scopes.size()) throw McpException.invalid("Each OAuth scope can be listed once.");
        return List.copyOf(scopes);
    }

    static Map<String, String> parameters(@Nullable Map<String, String> parameters) {
        if (parameters == null || parameters.size() > MAX_PARAMETERS)
            throw McpException.invalid("Provide at most " + MAX_PARAMETERS + " additional authorization parameters.");
        parameters.forEach((name, value) -> {
            if (name == null || !PARAMETER_NAME.matcher(name).matches() || RESERVED_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))
                    || value == null || value.length() > 1024 || value.chars().anyMatch(Character::isISOControl))
                throw McpException.invalid("An additional authorization parameter is not allowed.");
        });
        return new LinkedHashMap<>(parameters);
    }

    /** The model-facing name, or empty when the composed name does not fit model tool-name limits. */
    static Optional<String> modelToolName(String slug, String toolName) {
        String name = "mcp_" + slug + "_" + toolName;
        return MODEL_TOOL_NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }

    static List<ToolSnapshot> snapshot(List<McpToolDescriptor> tools) {
        var names = new HashSet<String>();
        var snapshots = new ArrayList<ToolSnapshot>(tools.size());
        for (McpToolDescriptor tool : tools) {
            if (tool.name().isBlank() || tool.name().length() > MAX_TOOL_NAME)
                throw McpException.toolSnapshotInvalid("The server lists a tool name longer than " + MAX_TOOL_NAME + " characters.");
            if (!names.add(tool.name())) throw McpException.toolSnapshotInvalid("The server lists the same tool name twice.");
            String title = tool.title() == null || tool.title().isBlank() ? null : tool.title().strip();
            if (title != null && title.length() > MAX_TOOL_TITLE)
                throw McpException.toolSnapshotInvalid("The server lists a tool title longer than " + MAX_TOOL_TITLE + " characters.");
            if (tool.description().length() > MAX_TOOL_DESCRIPTION)
                throw McpException.toolSnapshotInvalid("The server lists a tool description longer than " + MAX_TOOL_DESCRIPTION + " characters.");
            if (!"object".equals(tool.inputSchema().get("type")))
                throw McpException.toolSnapshotInvalid("The server lists a tool whose input schema is not an object.");
            String schema = json(tool.inputSchema());
            if (schema.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES)
                throw McpException.toolSnapshotInvalid("The server lists a tool input schema larger than " + MAX_SCHEMA_BYTES + " bytes.");
            var annotations = new LinkedHashMap<String, Boolean>();
            if (tool.readOnlyHint() != null) annotations.put("readOnlyHint", tool.readOnlyHint());
            if (tool.destructiveHint() != null) annotations.put("destructiveHint", tool.destructiveHint());
            if (tool.idempotentHint() != null) annotations.put("idempotentHint", tool.idempotentHint());
            if (tool.openWorldHint() != null) annotations.put("openWorldHint", tool.openWorldHint());
            snapshots.add(new ToolSnapshot(tool.name(), title, tool.description(), schema, json(annotations),
                    Boolean.TRUE.equals(tool.readOnlyHint())));
        }
        return List.copyOf(snapshots);
    }

    static @Nullable Boolean hint(String annotations, String name) {
        return readObject(annotations).get(name) instanceof Boolean value ? value : null;
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** Reads a stored JSON object of strings; stored data that does not match fails closed. */
    static Map<String, String> stringMap(String json) {
        var result = new LinkedHashMap<String, String>();
        readObject(json).forEach((name, value) -> {
            if (!(value instanceof String text)) throw McpException.credentialUnreadable();
            result.put(name, text);
        });
        return result;
    }

    static List<String> stringList(String json) {
        try {
            if (!(JSON.readValue(json, Object.class) instanceof List<?> values)) throw McpException.credentialUnreadable();
            return values.stream().map(value -> value instanceof String text ? text : failUnreadable()).toList();
        } catch (McpException unreadable) {
            throw unreadable;
        } catch (RuntimeException unreadable) {
            throw McpException.credentialUnreadable();
        }
    }

    private static Map<String, Object> readObject(String json) {
        try {
            if (!(JSON.readValue(json, Object.class) instanceof Map<?, ?> values)) throw McpException.credentialUnreadable();
            var result = new LinkedHashMap<String, Object>();
            values.forEach((name, value) -> result.put(String.valueOf(name), value));
            return result;
        } catch (McpException unreadable) {
            throw unreadable;
        } catch (RuntimeException unreadable) {
            throw McpException.credentialUnreadable();
        }
    }

    private static String failUnreadable() {
        throw McpException.credentialUnreadable();
    }
}
