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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** OpenAI Images: generation and whole-image edits with the connection's model. */
@Component
public final class OpenAiImageAdapter implements ImageGenerationAdapter {
    private static final ImageProviderCapabilities CAPABILITIES = new ImageProviderCapabilities("https://api.openai.com/v1",
            false, null, List.of(
            new KnownModel("gpt-image-2", "GPT Image 2", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1.5", "GPT Image 1.5", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1", "GPT Image 1", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false)));

    @Override public ImageProvider provider() { return ImageProvider.OPENAI_IMAGE; }
    @Override public ImageProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public String resolvedModel(String model) { return model.isBlank() ? "gpt-image-1" : model; }

    @Override public ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                                         @Nullable String size) throws IOException {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", resolvedModel(model));
        body.put("prompt", prompt);
        body.put("n", 1);
        if (size != null && !size.isBlank()) body.put("size", size);
        var first = call.json(base + "/images/generations", bearer(key), body).path("data").path(0);
        String revised = first.path("revised_prompt").asString("");
        return new ImageProviderClient.Result(base64(first.path("b64_json").asString("")), "image/png",
                revised.isBlank() ? null : revised);
    }

    /** The whole image is edited; input fidelity keeps faces and features for gpt-image models. */
    @Override public ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                                     ImageEditImages.Working image) throws IOException {
        String resolved = resolvedModel(model);
        var fields = new LinkedHashMap<String, String>();
        fields.put("model", resolved);
        fields.put("prompt", prompt);
        fields.put("n", "1");
        if (resolved.startsWith("gpt-image") && !resolved.endsWith("-mini")) fields.put("input_fidelity", "high");
        var first = call.multipart(base + "/images/edits", bearer(key), fields,
                List.of(new ImageHttp.FilePart("image", "image.png", "image/png", image.png()))).path("data").path(0);
        byte[] bytes = base64(first.path("b64_json").asString(""));
        String revised = first.path("revised_prompt").asString("");
        return new ImageProviderClient.Result(bytes, mediaType(bytes), revised.isBlank() ? null : revised);
    }
}
