package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ChatProviderAdapter;

@Schema(name = "TokenizerProfile")
public record ChatTokenizerProfileResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName
) {
    public static ChatTokenizerProfileResponse from(ChatProviderAdapter.TokenizerProfile value) {
        return new ChatTokenizerProfileResponse(value.id(), value.displayName());
    }
}
