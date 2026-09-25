package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.ai.ProviderAdapter;
import io.memoryos.ai.ProviderAdapters;
import java.util.List;

@Schema(name = "Descriptor")
public record ChatProviderAdapterResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProviderAdapter.CredentialRequirement credentialRequirement,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatTokenizerProfileResponse> tokenizerProfiles,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean nativeWebSearch,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatKnownModelResponse> knownModels
) {
    public static ChatProviderAdapterResponse from(ProviderAdapters.Descriptor value) {
        return new ChatProviderAdapterResponse(value.type(), value.credentialRequirement(),
                value.tokenizerProfiles().stream().map(ChatTokenizerProfileResponse::from).toList(),
                value.nativeWebSearch(),
                value.knownModels().stream().map(ChatKnownModelResponse::from).toList());
    }
}
