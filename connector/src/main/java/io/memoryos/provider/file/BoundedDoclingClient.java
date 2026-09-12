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
@org.jspecify.annotations.NullMarked
final class BoundedDoclingClient extends DoclingServeClient implements AutoCloseable {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String apiKey;
    private final HttpClient transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    private BoundedDoclingClient(Builder builder) { super(builder); apiKey = builder.accessKey; }

    static BoundedDoclingClient create(DoclingProperties properties) {
        var builder = new Builder().baseUrl(properties.endpoint()).connectTimeout(Duration.ofSeconds(5))
                .readTimeout(properties.timeout().plusSeconds(15)).logRequests(false).logResponses(false);
        builder.apiKey(properties.apiKey());
        return builder.build();
    }

    @Override protected <T> T readValue(String json, Class<T> type) { return mapper.readValue(json, type); }
    @Override protected <T> String writeValueAsString(T value) { return mapper.writeValueAsString(value); }

    ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse convertFile(java.nio.file.Path file, String extension,
                                                                                   DoclingProperties properties) throws IOException {
        String boundary = "memoryos-" + java.util.UUID.randomUUID();
        StringBuilder fields = new StringBuilder();
        // Serialize the SDK options once; multipart and JSON use the same names, values and defaults.
        mapper.valueToTree(properties.options()).properties().forEach(option -> {
            var values = option.getValue().isArray() ? option.getValue() : java.util.List.of(option.getValue());
            for (var value : values) fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                    .append(option.getKey()).append("\"\r\n\r\n").append(value.asString()).append("\r\n");
        });
        fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document")
                .append(extension).append("\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(fields.toString()),
                HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        var endpoint = java.net.URI.create(properties.endpoint().toString().replaceAll("/+$", "") + "/v1/convert/file");
        var request = HttpRequest.newBuilder(endpoint).timeout(properties.timeout().plusSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Accept", "application/json").POST(body).build();
        return execute(request, ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse.class);
    }

    @Override
    protected <T> T execute(HttpRequest request, Class<T> type) {
        try {
            if (!apiKey.isBlank()) request = HttpRequest.newBuilder(request, (name, _) -> !name.equalsIgnoreCase("X-Api-Key"))
                    .header("X-Api-Key", apiKey).build();
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
        private String accessKey = "";
        Builder(BoundedDoclingClient client) { super(client); accessKey = client.apiKey; }
        @Override public Builder apiKey(@org.jspecify.annotations.Nullable String key) {
            super.apiKey(key); accessKey = key == null ? "" : key; return this;
        }
        @Override public BoundedDoclingClient build() { return new BoundedDoclingClient(this); }
    }
}
