package io.memoryos.chat.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The transport, parsing and evidence bounds every Web adapter shares. Provider errors and credentials never become
 * model or UI output: a non-2xx answer is only "Web provider request failed".
 */
public final class WebCall {
    private static final int MAX_RESULTS = 20;
    private final WebHttp http;
    private final ObjectMapper json;

    WebCall(WebHttp http, ObjectMapper json) { this.http = http; this.json = json; }

    public JsonNode json(String method, String url, Map<String, String> headers, @Nullable Map<String, Object> body)
            throws IOException {
        var response = http.provider(method, URI.create(url), headers, body == null ? null : json.writeValueAsString(body));
        if (response.status() < 200 || response.status() >= 300) throw new IOException("Web provider request failed");
        return json.readTree(response.bytes());
    }

    /** Search hits under {@code path}: public URLs only, at most twenty, text taken from the first non-empty key. */
    public static List<WebProviderClient.Result> results(JsonNode root, String path, String urlKey, String textKey,
                                                         @Nullable String fallbackTextKey) {
        var results = new ArrayList<WebProviderClient.Result>();
        for (var item : root.at(path)) {
            try {
                String link = WebHttp.pageUri(item.path(urlKey).asString("")).toString();
                String text = item.path(textKey).asString("");
                if (text.isEmpty() && fallbackTextKey != null) text = item.path(fallbackTextKey).asString("");
                results.add(new WebProviderClient.Result(link, clipped(item.path("title").asString(link), 1024), clipped(text, 4000)));
                if (results.size() >= MAX_RESULTS) break;
            } catch (IllegalArgumentException ignored) { /* Invalid result URLs are not evidence. */ }
        }
        return List.copyOf(results);
    }

    /** One read page, bounded as the built-in reader bounds it. */
    public static WebProviderClient.Result page(String url, String title, String text) {
        return new WebProviderClient.Result(url, clipped(title, 1024), clipped(text, 16000));
    }

    /** The configured endpoint without trailing slashes, or the provider's public address when none is configured. */
    public static String base(WebConnectionService.Connection connection, String publicBase) {
        String base = endpoint(connection);
        return base.isEmpty() ? publicBase : base;
    }
    public static String endpoint(WebConnectionService.Connection connection) {
        return connection.endpoint().replaceAll("/+$", "");
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public static Map<String, String> bearer(String key) { return Map.of("Authorization", "Bearer " + key); }
    static String clipped(String value, int max) { return value.substring(0, Math.min(max, value.length())); }
}
