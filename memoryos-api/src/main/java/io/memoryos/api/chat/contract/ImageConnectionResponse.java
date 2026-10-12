package io.memoryos.api.chat.contract;

import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public record ImageConnectionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) ImageProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        boolean credentialConfigured, boolean active, long revision) {
    public static ImageConnectionResponse from(ImageConnectionService.View view) {
        return new ImageConnectionResponse(view.provider(), view.endpoint(), view.model(), view.credentialConfigured(), view.active(), view.revision());
    }
}
