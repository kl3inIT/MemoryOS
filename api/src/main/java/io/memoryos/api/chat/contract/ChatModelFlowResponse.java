package io.memoryos.api.chat.contract;

import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelFlow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ModelFlow", description = "Tenant model for one task; no model uses the conversation model")
public record ChatModelFlowResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ModelFlow flow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID modelConfigurationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "False when the model is set but no longer eligible; the task then uses the conversation model")
        boolean available,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static ChatModelFlowResponse from(ModelCatalogService.FlowView value) {
        return new ChatModelFlowResponse(value.flow(), value.modelConfigurationId(), value.available(), value.revision());
    }
}
