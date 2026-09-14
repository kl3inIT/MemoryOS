package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ChatProviderAdapter;
import io.memoryos.chat.catalog.ChatProviderAdapters;
import java.util.List;

@Schema(name = "Descriptor")
public record ChatProviderAdapterResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatProviderAdapter.CredentialRequirement credentialRequirement,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatTokenizerProfileResponse> tokenizerProfiles
) {
    public static ChatProviderAdapterResponse from(ChatProviderAdapters.Descriptor value) {
        return new ChatProviderAdapterResponse(value.type(), value.credentialRequirement(),
                value.tokenizerProfiles().stream().map(ChatTokenizerProfileResponse::from).toList());
    }
}
