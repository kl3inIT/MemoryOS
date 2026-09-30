package io.memoryos.chat.image.adapter;

import static io.memoryos.chat.image.ImageCall.base64;
import static io.memoryos.chat.image.ImageCall.bearer;
import static io.memoryos.chat.image.ImageCall.mediaType;

import io.memoryos.chat.image.ImageCall;
import io.memoryos.chat.image.ImageEditImages;
import io.memoryos.chat.image.ImageGenerationAdapter;
import io.memoryos.chat.image.ImageHttp;
import io.memoryos.chat.image.ImageProvider;
import io.memoryos.chat.image.ImageProviderCapabilities;
import io.memoryos.chat.image.ImageProviderCapabilities.KnownModel;
import io.memoryos.chat.image.ImageProviderClient;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Cloudflare Workers AI on the Tenant's account endpoint. Generation uses the configured model; every edit uses
 * FLUX.2 [klein] 9B (MEM-109): instruction editing that keeps unchanged content, preferred over 4B for quality at
 * about 1,300 neurons per 1024 px edit. SD 1.5 inpainting and img2img were rejected.
 */
@Component
public final class CloudflareWorkersAiImageAdapter implements ImageGenerationAdapter {
    private static final ImageProviderCapabilities CAPABILITIES = new ImageProviderCapabilities(null, true,
            new KnownModel("@cf/black-forest-labs/flux-2-klein-9b", "FLUX.2 klein 9B", "image/jpeg", List.of(), true, false),
            List.of(new KnownModel("@cf/black-forest-labs/flux-1-schnell", "FLUX.1 schnell", "image/jpeg", List.of(), false, false)));
    private static final String EDIT_MODEL = Objects.requireNonNull(CAPABILITIES.editModel()).modelName();
    private static final String ACCOUNT_BASE = "https://api.cloudflare.com/client/v4/accounts/";
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9a-f]{32}", Pattern.CASE_INSENSITIVE);

    @Override public ImageProvider provider() { return ImageProvider.CLOUDFLARE_WORKERS_AI; }
    @Override public ImageProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public String resolvedModel(String model) { return model.isBlank() ? "@cf/black-forest-labs/flux-1-schnell" : model; }

    /** A bare Cloudflare account ID expands to its account endpoint; every other value is stored as given. */
    @Override public String normalizeEndpoint(String endpoint) {
        return ACCOUNT_ID.matcher(endpoint).matches() ? ACCOUNT_BASE + endpoint.toLowerCase(Locale.ROOT) : endpoint;
    }

    /** Cloudflare wraps run output in {@code {"result": {...}}}; text-to-image returns base64 JPEG. */
    @Override public ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                                         @Nullable String size) throws IOException {
        requireAccount(base);
        var body = new LinkedHashMap<String, Object>();
        body.put("prompt", prompt);
        body.put("steps", 4);
        var root = call.json(base + "/ai/run/" + resolvedModel(model), bearer(key), body);
        return new ImageProviderClient.Result(base64(root.path("result").path("image").asString("")), "image/jpeg", null);
    }

    /** FLUX.2 [klein] reference editing: multipart input image; the output keeps the requested working size. */
    @Override public ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                                     ImageEditImages.Working image) throws IOException {
        requireAccount(base);
        var fields = new LinkedHashMap<String, String>();
        fields.put("prompt", prompt);
        fields.put("width", Integer.toString(image.width()));
        fields.put("height", Integer.toString(image.height()));
        var root = call.multipart(base + "/ai/run/" + EDIT_MODEL, bearer(key), fields,
                List.of(new ImageHttp.FilePart("input_image_0", "image.png", "image/png", image.png())));
        byte[] bytes = base64(root.path("result").path("image").asString(""));
        return new ImageProviderClient.Result(bytes, mediaType(bytes), null);
    }

    private static void requireAccount(String base) throws IOException {
        if (base.isEmpty()) throw new IOException("Cloudflare Workers AI requires an account endpoint");
    }
}
