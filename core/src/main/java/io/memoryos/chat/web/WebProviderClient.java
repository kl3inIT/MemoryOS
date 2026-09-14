package io.memoryos.chat.web;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jsoup.Jsoup;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small protocol adapters; provider errors and credentials never become model/UI output. */
@Component
public final class WebProviderClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebHttp http;
    private final WebConnectionService connections;
    private final MeterRegistry meters;
    public WebProviderClient(WebHttp http, WebConnectionService connections, MeterRegistry meters) {
        this.http = http; this.connections = connections; this.meters = meters;
    }
    public record Result(String url, String title, String text) {}

    public List<Result> search(WebConnectionService.Connection connection, String query) throws IOException {
        return measured(connection.provider().name(), "search", () -> searchRequest(connection, query));
    }
    private List<Result> searchRequest(WebConnectionService.Connection connection, String query) throws IOException {
        if (query == null || query.isBlank() || query.length() > 2000) throw new IllegalArgumentException("Invalid Web query");
        String key = connections.key(connection);
        String base = connection.endpoint().replaceAll("/+$", "");
        String url;
        Map<String, String> headers;
        Map<String, Object> body = null;
        String resultPath, urlKey = "url", textKey = "content", fallbackTextKey = null;
        switch (connection.provider()) {
            case BRAVE -> {
                url = (base.isEmpty() ? "https://api.search.brave.com" : base) + "/res/v1/web/search?q=" + encode(query) + "&count=20";
                headers = Map.of("X-Subscription-Token", key); resultPath = "/web/results"; textKey = "description";
            }
            case TAVILY -> {
                url = (base.isEmpty() ? "https://api.tavily.com" : base) + "/search"; headers = bearer(key);
                body = Map.of("query", query, "max_results", 20, "search_depth", "basic", "include_answer", false);
                resultPath = "/results";
            }
            case EXA -> {
                url = (base.isEmpty() ? "https://api.exa.ai" : base) + "/search"; headers = Map.of("x-api-key", key);
                body = Map.of("query", query, "numResults", 20); resultPath = "/results"; textKey = "text";
            }
            case SERPER -> {
                url = (base.isEmpty() ? "https://google.serper.dev" : base) + "/search"; headers = Map.of("X-API-KEY", key);
                body = Map.of("q", query, "num", 20); resultPath = "/organic"; urlKey = "link"; textKey = "snippet";
            }
            case GOOGLE_PSE -> {
                url = (base.isEmpty() ? "https://customsearch.googleapis.com" : base) + "/customsearch/v1?key=" + encode(key) + "&cx=" + encode(connection.engineId()) + "&q=" + encode(query) + "&num=10";
                headers = Map.of(); resultPath = "/items"; urlKey = "link"; textKey = "snippet";
            }
            case SEARXNG -> {
                url = base + "/search?q=" + encode(query) + "&format=json";
                headers = key.isEmpty() ? Map.of() : bearer(key); resultPath = "/results";
            }
            case NINEROUTER -> {
                // The gateway routes to the configured engine, which it names "model"; its own
                // endpoint may already carry the /search suffix an administrator copied from a URL.
                url = base.replaceAll("/search$", "") + "/search"; headers = bearer(key);
                body = Map.of("model", connection.engineId(), "query", query, "max_results", 20);
                resultPath = "/results"; textKey = "snippet"; fallbackTextKey = "content";
            }
            default -> throw new IllegalArgumentException("Provider does not support search");
        }
        var root = json(body == null ? "GET" : "POST", url, headers, body);
        var results = new ArrayList<Result>();
        for (var item : root.at(resultPath)) {
            try {
                String link = WebHttp.pageUri(item.path(urlKey).asString("")).toString();
                String text = item.path(textKey).asString("");
                if (text.isEmpty() && fallbackTextKey != null) text = item.path(fallbackTextKey).asString("");
                results.add(new Result(link, clipped(item.path("title").asString(link), 1024), clipped(text, 4000)));
                if (results.size() >= 20) break;
            } catch (IllegalArgumentException ignored) { /* Invalid result URLs are not evidence. */ }
        }
        return List.copyOf(results);
    }

    public Result read(WebConnectionService.@Nullable Connection connection, String url, Runnable checkActive) throws IOException {
        return measured(connection == null ? "BUILT_IN" : connection.provider().name(), "read", () -> readRequest(connection, url, checkActive));
    }
    private Result readRequest(WebConnectionService.@Nullable Connection connection, String url, Runnable checkActive) throws IOException {
        WebHttp.pageUri(url);
        checkActive.run();
        if (connection == null) {
            var response = http.page(url, checkActive);
            if (response.status() != 200) throw new IOException("Web page unavailable");
            String type = response.contentType().toLowerCase(Locale.ROOT);
            if (type.startsWith("application/pdf")) return WebPdfReader.read(response.bytes(), url, checkActive);
            if (!(type.startsWith("text/html") || type.startsWith("application/xhtml+xml") || type.startsWith("text/plain")))
                throw new IOException("Unsupported Web content type");
            if (type.startsWith("text/plain")) return new Result(url, url, clipped(new String(response.bytes(), StandardCharsets.UTF_8), 16000));
            var document = Jsoup.parse(new ByteArrayInputStream(response.bytes()), null, url);
            document.select("script,style,noscript,nav,footer,header,form,iframe").remove();
            return new Result(url, clipped(document.title().isBlank() ? url : document.title(), 1024), clipped(document.body().text(), 16000));
        }
        String key = connections.key(connection);
        String base = connection.endpoint().replaceAll("/+$", "");
        JsonNode root;
        switch (connection.provider()) {
            case TAVILY -> {
                root = json("POST", (base.isEmpty() ? "https://api.tavily.com" : base) + "/extract", bearer(key), Map.of("urls", List.of(url)));
                root = root.path("results").path(0);
                if (root.isMissingNode()) throw new IOException("Web extraction failed");
                return new Result(url, clipped(root.path("title").asString(url), 1024), clipped(root.path("raw_content").asString(""), 16000));
            }
            case EXA -> {
                root = json("POST", (base.isEmpty() ? "https://api.exa.ai" : base) + "/contents", Map.of("x-api-key", key), Map.of("ids", List.of(url), "text", true));
                root = root.path("results").path(0);
                if (root.isMissingNode()) throw new IOException("Web extraction failed");
                return new Result(url, clipped(root.path("title").asString(url), 1024), clipped(root.path("text").asString(""), 16000));
            }
            case FIRECRAWL -> {
                root = json("POST", (base.isEmpty() ? "https://api.firecrawl.dev" : base) + "/v2/scrape", bearer(key), Map.of("url", url, "formats", List.of("markdown"), "onlyMainContent", true));
                if (!root.path("success").asBoolean()) throw new IOException("Web extraction failed");
                root = root.path("data");
                return new Result(url, clipped(root.path("metadata").path("title").asString(url), 1024), clipped(root.path("markdown").asString(""), 16000));
            }
            default -> throw new IllegalArgumentException("Provider does not support content reading");
        }
    }
    private JsonNode json(String method, String url, Map<String, String> headers, @Nullable Map<String, Object> body) throws IOException {
        var response = http.provider(method, URI.create(url), headers, body == null ? null : JSON.writeValueAsString(body));
        if (response.status() < 200 || response.status() >= 300) throw new IOException("Web provider request failed");
        return JSON.readTree(response.bytes());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Map<String, String> bearer(String key) { return Map.of("Authorization", "Bearer " + key); }
    private static String clipped(String value, int max) { return value.substring(0, Math.min(max, value.length())); }

    @FunctionalInterface private interface Request<T> { T run() throws IOException; }
    private <T> T measured(String provider, String operation, Request<T> request) throws IOException {
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            T result = request.run();
            outcome = "succeeded";
            return result;
        } finally {
            // Bounded dimensions only. Calls are not a provider billing or token-usage estimate.
            meters.timer("memoryos.chat.web.request", "provider", provider, "operation", operation, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
