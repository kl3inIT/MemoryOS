package io.memoryos.chat.image.adapter;

import static io.memoryos.chat.image.ImageCall.fromDataUri;
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
 * fal.ai synchronous model endpoints: the model is the endpoint ID and its edit endpoint sits beside it. Sync mode
 * returns the image as a data URI, so no hosted file is fetched and fal keeps no request history of it.
 */
@Component
public final class FalImageAdapter implements ImageGenerationAdapter {
    private static final ImageProviderCapabilities CAPABILITIES = new ImageProviderCapabilities("https://fal.run",
            false, null, List.of(
            new KnownModel("fal-ai/nano-banana-2", "Nano Banana 2", "image/png", KnownModel.ASPECT_RATIOS, true, false),
            new KnownModel("fal-ai/flux-2-pro", "FLUX.2 Pro", "image/jpeg", KnownModel.ASPECT_RATIOS, true, false),
            new KnownModel("fal-ai/bytedance/seedream/v5/lite/text-to-image", "Seedream 5.0 Lite", "image/png", KnownModel.ASPECT_RATIOS, true, false)));
    /** FLUX and Seedream take a named size instead of an aspect ratio. */
    private static final Map<String, String> NAMED_SIZES =
            Map.of("1:1", "square_hd", "16:9", "landscape_16_9", "9:16", "portrait_16_9");

    @Override public ImageProvider provider() { return ImageProvider.FAL_AI; }
    @Override public ImageProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public String resolvedModel(String model) { return model.isBlank() ? "fal-ai/nano-banana-2" : model; }

    @Override public ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                                         @Nullable String size,
                                                         @Nullable ImageTemplate template) throws IOException {
        String resolved = resolvedModel(model);
        var body = request(prompt);
        if (size != null) {
            if (resolved.contains("nano-banana")) body.put("aspect_ratio", size);
            else if (NAMED_SIZES.containsKey(size)) body.put("image_size", NAMED_SIZES.get(size));
        }
        return image(call, base + "/" + resolved, key, body);
    }

    @Override public ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                                     ImageEditImages.Working image, @Nullable ImageTemplate template) throws IOException {
        var body = request(prompt);
        body.put("image_urls", List.of(pngDataUri(image.png())));
        return image(call, base + "/" + editEndpoint(resolvedModel(model)), key, body);
    }

    /** {@code …/text-to-image} edits at {@code …/edit}; any other endpoint at its {@code /edit} child. */
    private static String editEndpoint(String model) {
        if (model.endsWith("/edit")) return model;
        return model.endsWith("/text-to-image")
                ? model.substring(0, model.length() - "text-to-image".length()) + "edit"
                : model + "/edit";
    }

    private static LinkedHashMap<String, Object> request(String prompt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("prompt", prompt);
        body.put("sync_mode", true);
        return body;
    }

    private static ImageProviderClient.Result image(ImageCall call, String url, String key, Map<String, Object> body)
            throws IOException {
        var root = call.json(url, Map.of("Authorization", "Key " + key), body);
        byte[] bytes = fromDataUri(root.path("images").path(0).path("url").asString(""));
        return new ImageProviderClient.Result(bytes, mediaType(bytes), null);
    }
}
