package io.memoryos.api.chat.contract;

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
    public static ImageProviderResponse from(ImageProvider value) {
        var edit = value.editModel();
        return new ImageProviderResponse(value, value.requiresKey(), value.defaultEndpoint(), value.endpointRequired(),
                edit == null ? null : ImageKnownModelResponse.from(edit),
                value.knownModels().stream().map(ImageKnownModelResponse::from).toList());
    }
}
