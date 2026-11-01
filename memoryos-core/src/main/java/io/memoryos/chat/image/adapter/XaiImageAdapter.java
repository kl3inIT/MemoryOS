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
 * xAI Grok Imagine. Its images API is OpenAI-shaped but answers with a hosted URL unless asked for base64, and its
 * edits take JSON with the image as a data URI, not the multipart form OpenAI uses.
 */
@Component
public final class XaiImageAdapter implements ImageGenerationAdapter {
    private static final ImageProviderCapabilities CAPABILITIES = new ImageProviderCapabilities("https://api.x.ai/v1",
            false, null, List.of(
            new KnownModel("grok-imagine-image-2.0", "Grok Imagine Image 2.0", "image/jpeg", KnownModel.ASPECT_RATIOS, true, false)));

    @Override public ImageProvider provider() { return ImageProvider.XAI_IMAGE; }
    @Override public ImageProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public String resolvedModel(String model) { return model.isBlank() ? "grok-imagine-image-2.0" : model; }

    @Override public ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                                         @Nullable String size,
                                                         @Nullable ImageTemplate template) throws IOException {
        var body = request(model, prompt);
        if (size != null) body.put("aspect_ratio", size);
        return image(call, base + "/images/generations", key, body);
    }

    @Override public ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                                     ImageEditImages.Working image, @Nullable ImageTemplate template) throws IOException {
        var body = request(model, prompt);
        body.put("image", Map.of("url", pngDataUri(image.png()), "type", "image_url"));
        return image(call, base + "/images/edits", key, body);
    }

    private LinkedHashMap<String, Object> request(String model, String prompt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", resolvedModel(model));
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("response_format", "b64_json");
        return body;
    }

    private static ImageProviderClient.Result image(ImageCall call, String url, String key, Map<String, Object> body)
            throws IOException {
        var first = call.json(url, bearer(key), body).path("data").path(0);
        byte[] bytes = base64(first.path("b64_json").asString(""));
        String revised = first.path("revised_prompt").asString("");
        return new ImageProviderClient.Result(bytes, mediaType(bytes), revised.isBlank() ? null : revised);
    }
}
