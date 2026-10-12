package io.memoryos.api.chat.contract;

import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** An installed image protocol and the models its adapter serves; carries no Tenant configuration or credentials. */
public record ImageProviderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ImageProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean credentialRequired,
        @Nullable String defaultEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean endpointRequired,
        @Nullable ImageKnownModelResponse editModel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ImageKnownModelResponse> knownModels
) {
    public static ImageProviderResponse from(ImageConnectionService.Installed value) {
        var capabilities = value.capabilities();
        var edit = capabilities.editModel();
        return new ImageProviderResponse(value.provider(), capabilities.requiresKey(), capabilities.defaultEndpoint(),
                capabilities.endpointRequired(), edit == null ? null : ImageKnownModelResponse.from(edit),
                capabilities.knownModels().stream().map(ImageKnownModelResponse::from).toList());
    }
}
