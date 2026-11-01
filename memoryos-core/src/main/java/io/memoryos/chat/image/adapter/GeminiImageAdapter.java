package io.memoryos.chat.image.adapter;

import static io.memoryos.chat.image.ImageCall.base64;
import static io.memoryos.chat.image.ImageCall.mediaType;

import io.memoryos.chat.image.ImageCall;
import io.memoryos.chat.image.ImageEditImages;
import io.memoryos.chat.image.ImageGenerationAdapter;
import io.memoryos.chat.image.ImageProvider;
import io.memoryos.chat.image.ImageProviderCapabilities;
import io.memoryos.chat.image.ImageProviderCapabilities.KnownModel;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.chat.image.ImageTemplate;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Google Gemini native image models (Nano Banana) through {@code generateContent}: the prompt, and for an edit the
 * working image, go inline; the image comes back inline. Every catalog model generates and edits.
 */
@Component
public final class GeminiImageAdapter implements ImageGenerationAdapter {
    private static final ImageProviderCapabilities CAPABILITIES = new ImageProviderCapabilities(
            "https://generativelanguage.googleapis.com/v1", false, null, List.of(
            new KnownModel("gemini-nano-banana-2.1", "Nano Banana 2.1", "image/png", KnownModel.ASPECT_RATIOS, true, false),
            new KnownModel("gemini-3.1-flash-image", "Gemini 3.1 Flash Image", "image/png", KnownModel.ASPECT_RATIOS, true, false),
            new KnownModel("gemini-3-pro-image", "Gemini 3 Pro Image", "image/png", KnownModel.ASPECT_RATIOS, true, false)));

    @Override public ImageProvider provider() { return ImageProvider.GEMINI_IMAGE; }
    @Override public ImageProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public String resolvedModel(String model) { return model.isBlank() ? "gemini-nano-banana-2.1" : model; }

    @Override public ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                                         @Nullable String size,
                                                         @Nullable ImageTemplate template) throws IOException {
        var config = new LinkedHashMap<String, Object>();
        config.put("responseModalities", List.of("TEXT", "IMAGE"));
        if (size != null) config.put("responseFormat", Map.of("image", Map.of("aspectRatio", size)));
        return image(call, base, key, model, List.of(Map.of("text", prompt)), config);
    }

    /** The working image goes inline beside the instruction; the model keeps what the instruction leaves alone. */
    @Override public ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                                     ImageEditImages.Working image, @Nullable ImageTemplate template) throws IOException {
        Map<String, Object> inline = Map.of("inline_data", Map.of("mime_type", "image/png",
                "data", Base64.getEncoder().encodeToString(image.png())));
        return image(call, base, key, model, List.of(Map.of("text", prompt), inline),
                Map.of("responseModalities", List.of("TEXT", "IMAGE")));
    }

    private ImageProviderClient.Result image(ImageCall call, String base, String key, String model,
                                             List<Map<String, Object>> parts, Map<String, Object> config) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        body.put("generationConfig", config);
        var root = call.json(base + "/models/" + resolvedModel(model) + ":generateContent",
                Map.of("x-goog-api-key", key), body);
        byte[] bytes = base64(finalImage(root));
        return new ImageProviderClient.Result(bytes, mediaType(bytes), null);
    }

    /**
     * The last inline image that is not a thought: thinking models return interim images first. A safety block is
     * an answer without an image part, so it fails like any missing image.
     */
    private static String finalImage(JsonNode root) {
        String found = "";
        for (var part : root.path("candidates").path(0).path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue;
            var inline = part.has("inlineData") ? part.path("inlineData") : part.path("inline_data");
            String data = inline.path("data").asString("");
            if (!data.isEmpty()) found = data;
        }
        return found;
    }
}
