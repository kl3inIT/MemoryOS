package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatReportedModels", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChatReportedModelsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> models
) {
    public ChatReportedModelsResponse { models = List.copyOf(models); }
}
