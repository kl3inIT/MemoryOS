package io.memoryos.chat.image;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small protocol adapters; provider errors and credentials never become model/UI output. */
@Component
public final class ImageProviderClient {
    /**
     * Instruction editing that keeps unchanged content; SD 1.5 inpainting and img2img were rejected in MEM-109.
     * Klein 9B is preferred over 4B for quality at about 1,300 neurons per 1024 px edit; declared once in the
     * image model catalog.
     */
    static final String CLOUDFLARE_EDIT_MODEL = Objects.requireNonNull(ImageProvider.CLOUDFLARE_WORKERS_AI.editModel()).modelName();
    private static final String OPENAI_ENDPOINT = Objects.requireNonNull(ImageProvider.OPENAI_IMAGE.defaultEndpoint());
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ImageHttp http;
    private final ImageConnectionService connections;
    private final MeterRegistry meters;
    public ImageProviderClient(ImageHttp http, ImageConnectionService connections, MeterRegistry meters) {
        this.http = http; this.connections = connections; this.meters = meters;
    }
    public record Result(byte[] bytes, String mediaType, @Nullable String revisedPrompt) {}

    public Result generate(ImageConnectionService.Connection connection, String prompt, @Nullable String shape) throws IOException {
        return measured(connection.provider().name(), "generate", () -> generateRequest(connection, prompt, shape));
    }

    /** Edits a normalized working image from an English instruction; a mask is applied afterwards by the caller. */
    public Result edit(ImageConnectionService.Connection connection, String prompt, ImageEditImages.Working image) throws IOException {
        return measured(connection.provider().name(), "edit", () -> editRequest(connection, prompt, image));
    }

    private Result generateRequest(ImageConnectionService.Connection connection, String prompt, @Nullable String shape) throws IOException {
        validate(prompt);
        String key = connections.key(connection);
        String base = connection.endpoint().replaceAll("/+$", "");
        // The tool shape maps to a declared size of the configured model; unknown models and
        // models without declared sizes keep the provider default.
        String size = connection.provider().sizeFor(connection.model(), shape);
        return switch (connection.provider()) {
            case OPENAI_IMAGE -> openAi(base.isEmpty() ? OPENAI_ENDPOINT : base, key, connection.model(), prompt, size);
            case CLOUDFLARE_WORKERS_AI -> cloudflare(base, key, connection.model(), prompt);
        };
    }
    private Result editRequest(ImageConnectionService.Connection connection, String prompt, ImageEditImages.Working image) throws IOException {
        validate(prompt);
        var auth = Map.of("Authorization", "Bearer " + connections.key(connection));
        String base = connection.endpoint().replaceAll("/+$", "");
        return switch (connection.provider()) {
            case OPENAI_IMAGE -> openAiEdit(base.isEmpty() ? OPENAI_ENDPOINT : base, auth, connection.model(), prompt, image);
            case CLOUDFLARE_WORKERS_AI -> cloudflareEdit(base, auth, prompt, image);
        };
    }
    private static void validate(@Nullable String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) throw new IllegalArgumentException("Invalid image prompt");
    }

    private Result openAi(String base, String key, String model, String prompt, @Nullable String size) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model.isBlank() ? "gpt-image-1" : model);
        body.put("prompt", prompt);
        body.put("n", 1);
        if (size != null && !size.isBlank()) body.put("size", size);
        var first = json(base + "/images/generations", Map.of("Authorization", "Bearer " + key), body).path("data").path(0);
        String revised = first.path("revised_prompt").asString("");
        return new Result(base64(first.path("b64_json").asString("")), "image/png", revised.isBlank() ? null : revised);
    }
    private Result cloudflare(String base, String key, String model, String prompt) throws IOException {
        if (base.isEmpty()) throw new IOException("Cloudflare Workers AI requires an account endpoint");
        String m = model.isBlank() ? "@cf/black-forest-labs/flux-1-schnell" : model;
        var body = new LinkedHashMap<String, Object>();
        body.put("prompt", prompt);
        body.put("steps", 4);
        // Cloudflare wraps run output in {"result": {...}}; text-to-image returns base64 JPEG.
        var root = json(base + "/ai/run/" + m, Map.of("Authorization", "Bearer " + key), body);
        return new Result(base64(root.path("result").path("image").asString("")), "image/jpeg", null);
    }

    /** OpenAI image edits: the whole image is edited; input fidelity keeps faces and features for gpt-image models. */
    private Result openAiEdit(String base, Map<String, String> auth, String model, String prompt, ImageEditImages.Working image) throws IOException {
        String m = model.isBlank() ? "gpt-image-1" : model;
        var fields = new LinkedHashMap<String, String>();
        fields.put("model", m);
        fields.put("prompt", prompt);
        fields.put("n", "1");
        if (m.startsWith("gpt-image") && !m.endsWith("-mini")) fields.put("input_fidelity", "high");
        var first = parse(http.postMultipart(URI.create(base + "/images/edits"), auth, fields,
                List.of(new ImageHttp.FilePart("image", "image.png", "image/png", image.png())))).path("data").path(0);
        byte[] bytes = base64(first.path("b64_json").asString(""));
        String revised = first.path("revised_prompt").asString("");
        return new Result(bytes, mediaType(bytes), revised.isBlank() ? null : revised);
    }

    /** FLUX.2 [klein] reference editing: multipart input image; the output keeps the requested working size. */
    private Result cloudflareEdit(String base, Map<String, String> auth, String prompt, ImageEditImages.Working image) throws IOException {
        if (base.isEmpty()) throw new IOException("Cloudflare Workers AI requires an account endpoint");
        var fields = new LinkedHashMap<String, String>();
        fields.put("prompt", prompt);
        fields.put("width", Integer.toString(image.width()));
        fields.put("height", Integer.toString(image.height()));
        var root = parse(http.postMultipart(URI.create(base + "/ai/run/" + CLOUDFLARE_EDIT_MODEL), auth, fields,
                List.of(new ImageHttp.FilePart("input_image_0", "image.png", "image/png", image.png()))));
        byte[] bytes = base64(root.path("result").path("image").asString(""));
        return new Result(bytes, mediaType(bytes), null);
    }

    private JsonNode json(String url, Map<String, String> headers, Map<String, Object> body) throws IOException {
        return parse(http.post(URI.create(url), headers, JSON.writeValueAsString(body)));
    }
    private static JsonNode parse(ImageHttp.Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) throw new IOException("Image provider request failed");
        return JSON.readTree(response.bytes());
    }
    private static byte[] base64(String value) throws IOException {
        if (value.isEmpty()) throw new IOException("Image provider returned no image");
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid image encoding"); }
    }
    /** The stored media type follows the returned bytes, not the provider's documentation. */
    private static String mediaType(byte[] bytes) throws IOException {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        throw new IOException("Unsupported image format");
    }
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
            meters.timer("memoryos.chat.image.request", "provider", provider, "operation", operation, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
