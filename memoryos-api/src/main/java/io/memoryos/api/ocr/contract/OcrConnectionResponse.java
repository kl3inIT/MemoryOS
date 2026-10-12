package io.memoryos.api.ocr.contract;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A saved OCR connection; {@code active} marks the one that reads scans and images. */
public record OcrConnectionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) OcrProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,
        boolean credentialConfigured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DataBoundary dataBoundary,
        @Schema(description = "The catalog model a model connection reads with") @Nullable UUID modelConfigurationId,
        boolean active, long revision) {
    public static OcrConnectionResponse from(OcrConnectionService.View view) {
        return new OcrConnectionResponse(view.provider(), view.endpoint(), view.credentialConfigured(),
                view.dataBoundary(), view.modelConfigurationId(), view.active(), view.revision());
    }
}
