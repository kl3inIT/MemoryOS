package io.memoryos.api.chat.contract;

import io.memoryos.connector.SourceSearchService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ChatLibrarySourceOption", description = "A Source the viewer may narrow the documents view to")
public record ChatLibrarySourceOptionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"FILE", "GOOGLE_DRIVE", "SHAREPOINT"})
        String type
) {

    public static ChatLibrarySourceOptionResponse from(SourceSearchService.SourceOption option) {
        return new ChatLibrarySourceOptionResponse(option.id(), option.name(), option.type().name());
    }
}
