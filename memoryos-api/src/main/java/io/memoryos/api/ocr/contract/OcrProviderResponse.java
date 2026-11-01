package io.memoryos.api.ocr.contract;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ocr.OcrProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * An installed OCR reader type: whether a connection needs a key, whether it names a catalog model instead of a
 * service address, where its data goes by default, and a cloud service's public address.
 */
public record OcrProviderResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) OcrProvider provider,
        boolean requiresKey, boolean usesModel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DataBoundary dataBoundary,
        @Nullable String defaultEndpoint) {
    public static OcrProviderResponse from(OcrProvider provider) {
        return new OcrProviderResponse(provider, provider.requiresKey(), provider.usesModel(), provider.dataBoundary(),
                provider.defaultEndpoint());
    }
}
