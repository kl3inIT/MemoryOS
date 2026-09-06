package io.memoryos.provider.file;

import ai.docling.serve.client.DoclingServeClient;
import ai.docling.serve.client.DoclingServeClientException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

/** Official SDK operations with a bounded response transport; no response/body logging. */
final class BoundedDoclingClient extends DoclingServeClient implements AutoCloseable {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    private BoundedDoclingClient(Builder builder) { super(builder); }

    static BoundedDoclingClient create(DoclingProperties properties) {
        return new Builder().baseUrl(properties.endpoint()).connectTimeout(Duration.ofSeconds(5))
                .readTimeout(properties.timeout().plusSeconds(15)).logRequests(false).logResponses(false).build();
    }

    @Override protected <T> T readValue(String json, Class<T> type) { return mapper.readValue(json, type); }
    @Override protected <T> String writeValueAsString(T value) { return mapper.writeValueAsString(value); }

    @Override
    protected <T> T execute(HttpRequest request, Class<T> type) {
        try {
            var response = transport.send(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 67_108_864));
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new IllegalStateException("Docling redirects are not allowed");
            }
            return getResponse(request, response, type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DoclingServeClientException(e);
        } catch (IOException e) {
            throw new DoclingServeClientException(e);
        }
    }

    @Override public Builder toBuilder() { return new Builder(this); }
    @Override public void close() { transport.shutdownNow(); }

    static final class Builder extends DoclingServeClientBuilder<BoundedDoclingClient, Builder> {
        Builder() { }
        Builder(BoundedDoclingClient client) { super(client); }
        @Override public BoundedDoclingClient build() { return new BoundedDoclingClient(this); }
    }
}
