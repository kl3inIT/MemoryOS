package io.memoryos.chat.web;

import org.springframework.modulith.NamedInterface;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Web search and page reading for Chat. The selected provider's adapter shapes each request; this client owns the
 * credential lookup, the built-in reader and one observation per provider call. Provider errors and credentials
 * never become model/UI output.
 */
@Component
@NamedInterface("web")
public final class WebProviderClient {
    private final WebHttp http;
    private final WebConnectionService connections;
    private final WebAdapterRegistry adapters;
    private final WebCall call;
    private final ObservationRegistry observations;
    public WebProviderClient(WebHttp http, WebConnectionService connections, WebAdapterRegistry adapters, ObjectMapper json,
                             ObservationRegistry observations) {
        this.http = http; this.connections = connections; this.adapters = adapters; this.observations = observations;
        this.call = new WebCall(http, json);
    }
    public record Result(String url, String title, String text) {}

    public WebProviderCapabilities capabilities(WebProvider provider) { return adapters.capabilities(provider); }
    public boolean searches(WebProvider provider) { return adapters.searches(provider); }
    public boolean reads(WebProvider provider) { return adapters.reads(provider); }
    public boolean listsEngines(WebProvider provider) { return adapters.engines(provider).isPresent(); }

    public List<Result> search(WebConnectionService.Connection connection, String query) throws IOException {
        return measured(connection.provider().name(), "search", () -> {
            if (query == null || query.isBlank() || query.length() > 2000) throw new IllegalArgumentException("Invalid Web query");
            var adapter = adapters.search(connection.provider())
                    .orElseThrow(() -> new IllegalArgumentException("Provider does not support search"));
            return adapter.search(call, connection, connections.key(connection), query);
        });
    }

    /** The engines a gateway offers, for a provider whose adapter lists them. */
    public List<String> engines(WebProvider provider, String endpoint, String key) throws IOException {
        var adapter = adapters.engines(provider).orElseThrow(() -> new IllegalArgumentException("Provider has no engine list"));
        return measured(provider.name(), "engines", () -> adapter.engines(call, endpoint, key));
    }

    public Result read(WebConnectionService.@Nullable Connection connection, String url, Runnable checkActive) throws IOException {
        return measured(connection == null ? "BUILT_IN" : connection.provider().name(), "read", () -> {
            WebHttp.pageUri(url);
            checkActive.run();
            if (connection == null) return builtIn(url, checkActive);
            var adapter = adapters.content(connection.provider())
                    .orElseThrow(() -> new IllegalArgumentException("Provider does not support content reading"));
            return adapter.read(call, connection, connections.key(connection), url);
        });
    }

    private Result builtIn(String url, Runnable checkActive) throws IOException {
        var response = http.page(url, checkActive);
        if (response.status() != 200) throw new IOException("Web page unavailable");
        String type = response.contentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("application/pdf")) return WebPdfReader.read(response.bytes(), url, checkActive);
        if (!(type.startsWith("text/html") || type.startsWith("application/xhtml+xml") || type.startsWith("text/plain")))
            throw new IOException("Unsupported Web content type");
        if (type.startsWith("text/plain")) return WebCall.page(url, url, new String(response.bytes(), StandardCharsets.UTF_8));
        var document = Jsoup.parse(new ByteArrayInputStream(response.bytes()), null, url);
        document.select("script,style,noscript,nav,footer,header,form,iframe").remove();
        return WebCall.page(url, document.title().isBlank() ? url : document.title(), document.body().text());
    }

    @FunctionalInterface private interface Request<T> { T run() throws IOException; }

    /**
     * One observation per provider call, {@code memoryos.chat.web.request} with bounded {@code provider},
     * {@code operation} and {@code outcome} keys; its timer is what the Chat & AI dashboard reads. Calls are not a
     * provider billing or token-usage estimate.
     */
    private <T> T measured(String provider, String operation, Request<T> request) throws IOException {
        var observation = Observation.createNotStarted("memoryos.chat.web.request", observations)
                .lowCardinalityKeyValue("provider", provider).lowCardinalityKeyValue("operation", operation);
        observation.start();
        String outcome = "failed";
        try (var _ = observation.openScope()) {
            T result = request.run();
            outcome = "succeeded";
            return result;
        } catch (IOException | RuntimeException failure) {
            // The exception type only: provider failures can carry request details.
            observation.error(new IllegalStateException(failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }
}
