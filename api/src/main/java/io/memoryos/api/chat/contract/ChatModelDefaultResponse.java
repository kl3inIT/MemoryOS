package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "Default")
public record ChatModelDefaultResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID modelConfigurationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static ChatModelDefaultResponse from(ModelCatalogRepository.Default value) {
        return new ChatModelDefaultResponse(value.modelConfigurationId(), value.revision());
    }
}
