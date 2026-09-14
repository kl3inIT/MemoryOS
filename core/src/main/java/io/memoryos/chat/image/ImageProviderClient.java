package io.memoryos.chat.image;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small protocol adapters; provider errors and credentials never become model/UI output. */
@Component
public final class ImageProviderClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ImageHttp http;
    private final ImageConnectionService connections;
    private final MeterRegistry meters;
    public ImageProviderClient(ImageHttp http, ImageConnectionService connections, MeterRegistry meters) {
        this.http = http; this.connections = connections; this.meters = meters;
    }
    public record Result(byte[] bytes, String mediaType, @Nullable String revisedPrompt) {}

    public Result generate(ImageConnectionService.Connection connection, String prompt, @Nullable String size) throws IOException {
        return measured(connection.provider().name(), () -> generateRequest(connection, prompt, size));
    }
    private Result generateRequest(ImageConnectionService.Connection connection, String prompt, @Nullable String size) throws IOException {
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) throw new IllegalArgumentException("Invalid image prompt");
        String key = connections.key(connection);
        String base = connection.endpoint().replaceAll("/+$", "");
        return switch (connection.provider()) {
            case OPENAI_IMAGE -> openAi(base.isEmpty() ? "https://api.openai.com/v1" : base, key, connection.model(), prompt, size);
            case CLOUDFLARE_WORKERS_AI -> cloudflare(base, key, connection.model(), prompt);
        };
    }
    private Result openAi(String base, String key, String model, String prompt, @Nullable String size) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model.isBlank() ? "gpt-image-1" : model);
        body.put("prompt", prompt);
        body.put("n", 1);
        if (size != null && !size.isBlank()) body.put("size", size);
        var first = json(base + "/images/generations", Map.of("Authorization", "Bearer " + key), body).path("data").path(0);
        String b64 = first.path("b64_json").asString("");
        if (b64.isEmpty()) throw new IOException("Image provider returned no image");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid image encoding"); }
        String revised = first.path("revised_prompt").asString("");
        return new Result(bytes, "image/png", revised.isBlank() ? null : revised);
    }
    private Result cloudflare(String base, String key, String model, String prompt) throws IOException {
        if (base.isEmpty()) throw new IOException("Cloudflare Workers AI requires an account endpoint");
        String m = model.isBlank() ? "@cf/black-forest-labs/flux-1-schnell" : model;
        var body = new LinkedHashMap<String, Object>();
        body.put("prompt", prompt);
        body.put("steps", 4);
        // Cloudflare wraps run output in {"result": {...}}; text-to-image returns base64 JPEG.
        var root = json(base + "/ai/run/" + m, Map.of("Authorization", "Bearer " + key), body);
        String b64 = root.path("result").path("image").asString("");
        if (b64.isEmpty()) throw new IOException("Image provider returned no image");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid image encoding"); }
        return new Result(bytes, "image/jpeg", null);
    }
    private JsonNode json(String url, Map<String, String> headers, Map<String, Object> body) throws IOException {
        var response = http.post(URI.create(url), headers, JSON.writeValueAsString(body));
        if (response.status() < 200 || response.status() >= 300) throw new IOException("Image provider request failed");
        return JSON.readTree(response.bytes());
    }
    @FunctionalInterface private interface Request<T> { T run() throws IOException; }
    private <T> T measured(String provider, Request<T> request) throws IOException {
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            T result = request.run();
            outcome = "succeeded";
            return result;
        } finally {
            // Bounded dimensions only. Calls are not a provider billing or token-usage estimate.
            meters.timer("memoryos.chat.image.request", "provider", provider, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
