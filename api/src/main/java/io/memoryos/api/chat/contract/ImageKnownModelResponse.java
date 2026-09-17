package io.memoryos.api.chat.contract;

import io.memoryos.chat.image.ImageProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Published image model metadata; runtime behaviour follows the connection's stored model. */
public record ImageKnownModelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String outputMediaType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> sizes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean edit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean deprecated
) {
    public static ImageKnownModelResponse from(ImageProvider.KnownModel value) {
        return new ImageKnownModelResponse(value.modelName(), value.displayName(), value.outputMediaType(),
                value.sizes(), value.edit(), value.deprecated());
    }
}
