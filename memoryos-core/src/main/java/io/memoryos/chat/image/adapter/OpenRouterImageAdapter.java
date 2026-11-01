package io.memoryos.chat.image.adapter;

import static io.memoryos.chat.image.ImageCall.base64;
import static io.memoryos.chat.image.ImageCall.bearer;
import static io.memoryos.chat.image.ImageCall.mediaType;
import static io.memoryos.chat.image.ImageCall.pngDataUri;

import io.memoryos.chat.image.ImageCall;
import io.memoryos.chat.image.ImageEditImages;
import io.memoryos.chat.image.ImageGenerationAdapter;
import io.memoryos.chat.image.ImageProvider;
import io.memoryos.chat.image.ImageProviderCapabilities;
import io.memoryos.chat.image.ImageProviderCapabilities.KnownModel;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.chat.image.ImageTemplate;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * OpenRouter's Image API: one request shape for many vendors' models, the image always returned as base64. An edit
 * sends the working image as an input reference with the instruction; the catalog lists models that take one.
 */
@Component
public final class OpenRouterImageAdapter implements ImageGenerationAdapter {
    private static final ImageProviderCapabilities CAPABILITIES = new ImageProviderCapabilities(
            "https://openrouter.ai/api/v1", false, null, List.of(
            new KnownModel("google/gemini-nano-banana-2.1", "Nano Banana 2.1", "image/png", KnownModel.ASPECT_RATIOS, true, false),
            new KnownModel("openai/gpt-image-2", "GPT Image 2", "image/png", KnownModel.ASPECT_RATIOS, true, false),
            new KnownModel("black-forest-labs/flux.2-pro", "FLUX.2 Pro", "image/jpeg", KnownModel.ASPECT_RATIOS, true, false),
            new KnownModel("bytedance-seed/seedream-5-0-pro", "Seedream 5.0 Pro", "image/jpeg", KnownModel.ASPECT_RATIOS, true, false)));

    @Override public ImageProvider provider() { return ImageProvider.OPENROUTER_IMAGE; }
    @Override public ImageProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public String resolvedModel(String model) { return model.isBlank() ? "google/gemini-nano-banana-2.1" : model; }

    @Override public ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                                         @Nullable String size,
                                                         @Nullable ImageTemplate template) throws IOException {
        var body = request(model, prompt);
        if (size != null) body.put("aspect_ratio", size);
        return image(call, base, key, body);
    }

    @Override public ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                                     ImageEditImages.Working image, @Nullable ImageTemplate template) throws IOException {
        var body = request(model, prompt);
        body.put("input_references", List.of(Map.of("type", "image_url", "image_url", Map.of("url", pngDataUri(image.png())))));
        return image(call, base, key, body);
    }

    private LinkedHashMap<String, Object> request(String model, String prompt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", resolvedModel(model));
        body.put("prompt", prompt);
        body.put("n", 1);
        return body;
    }

    private static ImageProviderClient.Result image(ImageCall call, String base, String key, Map<String, Object> body)
            throws IOException {
        byte[] bytes = base64(call.json(base + "/images", bearer(key), body).path("data").path(0).path("b64_json").asString(""));
        return new ImageProviderClient.Result(bytes, mediaType(bytes), null);
    }
}
