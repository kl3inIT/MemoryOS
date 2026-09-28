package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatLibraryContentMatch")
public record ChatLibraryContentMatchResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatLibraryFileResponse file,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Up to three matching passages")
        List<ChatLibraryPassageResponse> passages
) {}
