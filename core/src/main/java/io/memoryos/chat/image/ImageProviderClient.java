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
     * image model catalog and used for Cloudflare models that cannot edit themselves.
     */
    static final String CLOUDFLARE_EDIT_MODEL = Objects.requireNonNull(ImageProvider.CLOUDFLARE_WORKERS_AI.editModel()).modelName();
    private static final String OPENAI_ENDPOINT = Objects.requireNonNull(ImageProvider.OPENAI_IMAGE.defaultEndpoint());
    private static final String GEMINI_ENDPOINT = Objects.requireNonNull(ImageProvider.GOOGLE_GEMINI_IMAGE.defaultEndpoint());
    /** Workers AI FLUX.2 models take multipart form input for generation as well as editing. */
    private static final String CLOUDFLARE_FLUX_2 = "@cf/black-forest-labs/flux-2-";
    private static final String CLOUDFLARE_DEFAULT_SIZE = "1024x1024";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ImageHttp http;
    private final ImageConnectionService connections;
    private final MeterRegistry meters;
    public ImageProviderClient(ImageHttp http, ImageConnectionService connections, MeterRegistry meters) {
        this.http = http; this.connections = connections; this.meters = meters;
    }
    public record Result(byte[] bytes, String mediaType, @Nullable String revisedPrompt) {}

    public Result generate(ImageConnectionService.Connection connection, String prompt, @Nullable String shape) throws IOException {
        return generate(connection, prompt, shape, null);
    }

    /** An override key authenticates an unsaved probe; null resolves the connection's stored credential. */
    public Result generate(ImageConnectionService.Connection connection, String prompt, @Nullable String shape, @Nullable String key) throws IOException {
        return measured(connection.provider().name(), "generate", () -> generateRequest(connection, prompt, shape, key));
    }

    /** Edits a normalized working image from an English instruction; a mask is applied afterwards by the caller. */
    public Result edit(ImageConnectionService.Connection connection, String prompt, ImageEditImages.Working image) throws IOException {
        return measured(connection.provider().name(), "edit", () -> editRequest(connection, prompt, image));
    }

    private Result generateRequest(ImageConnectionService.Connection connection, String prompt, @Nullable String shape, @Nullable String key) throws IOException {
        validate(prompt);
        var provider = connection.provider();
        var auth = auth(provider, key != null ? key : connections.key(connection));
        String model = connection.model();
        String base = base(connection);
        // The tool shape maps to a declared size of the configured model; unknown models and
        // models without declared sizes keep the provider default.
        String size = provider.sizeFor(model, shape);
        return switch (provider) {
            case OPENAI_IMAGE, AZURE_OPENAI_IMAGE -> openAi(base, auth, model, prompt, size, false);
            case OPENAI_COMPATIBLE_IMAGE -> openAi(base, auth, model, prompt, size, true);
            case GOOGLE_GEMINI_IMAGE -> model.startsWith("imagen-")
                    ? imagen(base, auth, model, prompt, ImageProvider.aspectRatioFor(shape))
                    : gemini(base, auth, model, prompt, ImageProvider.aspectRatioFor(shape), null);
            case CLOUDFLARE_WORKERS_AI -> cloudflare(base, auth, model, prompt, size);
        };
    }
    private Result editRequest(ImageConnectionService.Connection connection, String prompt, ImageEditImages.Working image) throws IOException {
        validate(prompt);
        var provider = connection.provider();
        var auth = auth(provider, connections.key(connection));
        String model = provider.editModelFor(connection.model());
        String base = base(connection);
        return switch (provider) {
            case OPENAI_IMAGE, AZURE_OPENAI_IMAGE -> openAiEdit(base, auth, model, prompt, image, true);
            case OPENAI_COMPATIBLE_IMAGE -> openAiEdit(base, auth, model, prompt, image, false);
            case GOOGLE_GEMINI_IMAGE -> gemini(base, auth, model, prompt, null, image);
            case CLOUDFLARE_WORKERS_AI -> cloudflareEdit(base, auth, model, prompt, image);
        };
    }
    private static void validate(@Nullable String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) throw new IllegalArgumentException("Invalid image prompt");
    }
    /** Azure and Google authenticate with their own key headers; every other protocol uses a bearer token. */
    private static Map<String, String> auth(ImageProvider provider, String key) {
        return switch (provider) {
            case AZURE_OPENAI_IMAGE -> Map.of("api-key", key);
            case GOOGLE_GEMINI_IMAGE -> Map.of("x-goog-api-key", key);
            default -> Map.of("Authorization", "Bearer " + key);
        };
    }
    private static String base(ImageConnectionService.Connection connection) throws IOException {
        String base = connection.endpoint().replaceAll("/+$", "");
        if (!base.isEmpty()) return base;
        return switch (connection.provider()) {
            case OPENAI_IMAGE -> OPENAI_ENDPOINT;
            case GOOGLE_GEMINI_IMAGE -> GEMINI_ENDPOINT;
            case CLOUDFLARE_WORKERS_AI -> throw new IOException("Cloudflare Workers AI requires an account endpoint");
            case AZURE_OPENAI_IMAGE, OPENAI_COMPATIBLE_IMAGE -> throw new IOException("Image provider requires an endpoint");
        };
    }

    /** OpenAI image protocol; gateways default to URL output, so compatible endpoints are asked for inline base64. */
    private Result openAi(String base, Map<String, String> auth, String model, String prompt, @Nullable String size,
                          boolean inlineOutput) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model.isBlank() ? "gpt-image-1" : model);
        body.put("prompt", prompt);
        body.put("n", 1);
        if (size != null && !size.isBlank()) body.put("size", size);
        if (inlineOutput) body.put("response_format", "b64_json");
        var first = json(base + "/images/generations", auth, body).path("data").path(0);
        String revised = first.path("revised_prompt").asString("");
        byte[] bytes = base64(first.path("b64_json").asString(""));
        return new Result(bytes, mediaType(bytes), revised.isBlank() ? null : revised);
    }

    /** Gemini image models answer generateContent with inline image parts; an input image turns the call into an edit. */
    private Result gemini(String base, Map<String, String> auth, String model, String prompt, @Nullable String aspectRatio,
                          ImageEditImages.@Nullable Working image) throws IOException {
        var parts = new java.util.ArrayList<Map<String, Object>>();
        parts.add(Map.of("text", prompt));
        if (image != null) {
            var inline = new LinkedHashMap<String, Object>();
            inline.put("mimeType", "image/png");
            inline.put("data", Base64.getEncoder().encodeToString(image.png()));
            parts.add(Map.of("inlineData", inline));
        }
        var config = new LinkedHashMap<String, Object>();
        config.put("responseModalities", List.of("TEXT", "IMAGE"));
        if (aspectRatio != null) config.put("imageConfig", Map.of("aspectRatio", aspectRatio));
        var body = new LinkedHashMap<String, Object>();
        body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        body.put("generationConfig", config);
        var root = json(base + "/models/" + model + ":generateContent", auth, body);
        for (var part : root.path("candidates").path(0).path("content").path("parts")) {
            var data = part.path("inlineData").path("data").asString("");
            if (!data.isEmpty()) {
                byte[] bytes = base64(data);
                return new Result(bytes, mediaType(bytes), null);
            }
        }
        throw new IOException("Image provider returned no image");
    }

    /** Imagen generation through the Gemini API predict method. */
    private Result imagen(String base, Map<String, String> auth, String model, String prompt, @Nullable String aspectRatio) throws IOException {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("sampleCount", 1);
        if (aspectRatio != null) parameters.put("aspectRatio", aspectRatio);
        var body = new LinkedHashMap<String, Object>();
        body.put("instances", List.of(Map.of("prompt", prompt)));
        body.put("parameters", parameters);
        var first = json(base + "/models/" + model + ":predict", auth, body).path("predictions").path(0);
        byte[] bytes = base64(first.path("bytesBase64Encoded").asString(""));
        return new Result(bytes, mediaType(bytes), null);
    }

    /**
     * Workers AI: FLUX.2 takes multipart input, other models JSON. FLUX and Lucid answer {"result": {"image"}}
     * with base64; Phoenix and SDXL Lightning answer with the image bytes themselves.
     */
    private Result cloudflare(String base, Map<String, String> auth, String model, String prompt, @Nullable String size) throws IOException {
        String m = model.isBlank() ? "@cf/black-forest-labs/flux-1-schnell" : model;
        URI uri = URI.create(base + "/ai/run/" + m);
        ImageHttp.Response response;
        if (m.startsWith(CLOUDFLARE_FLUX_2)) {
            var dimensions = (size == null ? CLOUDFLARE_DEFAULT_SIZE : size).split("x");
            var fields = new LinkedHashMap<String, String>();
            fields.put("prompt", prompt);
            fields.put("width", dimensions[0]);
            fields.put("height", dimensions[1]);
            response = http.postMultipart(uri, auth, fields, List.of());
        } else {
            var body = new LinkedHashMap<String, Object>();
            body.put("prompt", prompt);
            // FLUX.1 schnell allows at most 8 steps; other models keep their own defaults.
            if (m.equals("@cf/black-forest-labs/flux-1-schnell")) body.put("steps", 4);
            if (size != null) {
                var dimensions = size.split("x");
                body.put("width", Integer.parseInt(dimensions[0]));
                body.put("height", Integer.parseInt(dimensions[1]));
            }
            response = http.post(uri, auth, JSON.writeValueAsString(body));
        }
        return cloudflareResult(response);
    }

    /** OpenAI image edits: the whole image is edited; input fidelity keeps faces and features for gpt-image models. */
    private Result openAiEdit(String base, Map<String, String> auth, String model, String prompt, ImageEditImages.Working image,
                              boolean inputFidelity) throws IOException {
        String m = model.isBlank() ? "gpt-image-1" : model;
        var fields = new LinkedHashMap<String, String>();
        fields.put("model", m);
        fields.put("prompt", prompt);
        fields.put("n", "1");
        if (inputFidelity && m.startsWith("gpt-image") && !m.endsWith("-mini")) fields.put("input_fidelity", "high");
        var first = parse(http.postMultipart(URI.create(base + "/images/edits"), auth, fields,
                List.of(new ImageHttp.FilePart("image", "image.png", "image/png", image.png())))).path("data").path(0);
        byte[] bytes = base64(first.path("b64_json").asString(""));
        String revised = first.path("revised_prompt").asString("");
        return new Result(bytes, mediaType(bytes), revised.isBlank() ? null : revised);
    }

    /** FLUX.2 reference editing: multipart input image; the output keeps the requested working size. */
    private Result cloudflareEdit(String base, Map<String, String> auth, String model, String prompt, ImageEditImages.Working image) throws IOException {
        var fields = new LinkedHashMap<String, String>();
        fields.put("prompt", prompt);
        fields.put("width", Integer.toString(image.width()));
        fields.put("height", Integer.toString(image.height()));
        return cloudflareResult(http.postMultipart(URI.create(base + "/ai/run/" + model), auth, fields,
                List.of(new ImageHttp.FilePart("input_image_0", "image.png", "image/png", image.png()))));
    }

    private static Result cloudflareResult(ImageHttp.Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) throw new IOException("Image provider request failed");
        byte[] bytes = isImage(response.bytes()) ? response.bytes()
                : base64(JSON.readTree(response.bytes()).path("result").path("image").asString(""));
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
    private static boolean isImage(byte[] bytes) {
        try { mediaType(bytes); return true; } catch (IOException notImage) { return false; }
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
