package io.memoryos.chat.image;

import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * One image provider's protocol: generation and editing. {@link ImageAdapterRegistry} holds exactly one per
 * {@link ImageProvider}; {@link ImageCall} owns transport, parsing and image decoding.
 */
public interface ImageGenerationAdapter {
    ImageProvider provider();
    ImageProviderCapabilities capabilities();

    /** The endpoint to store for what a manager typed; most providers store it as given. */
    default String normalizeEndpoint(String endpoint) { return endpoint; }

    /** The model a request names: the connection's, or the provider default when it names none. */
    String resolvedModel(String model);

    /** The model an edit or generation is recorded under in the AI usage ledger. */
    default String usageModel(String model, boolean edit) {
        var editModel = capabilities().editModel();
        return edit && editModel != null ? editModel.modelName() : resolvedModel(model);
    }

    ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                        @Nullable String size) throws IOException;

    /** Edits a normalized working image from an English instruction; a mask is applied afterwards by the caller. */
    ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                    ImageEditImages.Working image) throws IOException;
}
