package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelCatalogService;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatPersonaPage")
public record ChatPersonaPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatPersonaResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String nextCursor
) {
    public static ChatPersonaPageResponse from(ModelCatalogService.PersonaPage value) {
        return new ChatPersonaPageResponse(value.items().stream().map(ChatPersonaResponse::from).toList(), value.nextCursor());
    }
}
