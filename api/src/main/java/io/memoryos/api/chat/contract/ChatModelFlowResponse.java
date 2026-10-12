package io.memoryos.api.chat.contract;

import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ReasoningEffort;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ModelFlow", description = "What one task runs on: a Tenant model, a System One connection for a "
        + "classifying task, or with neither the conversation model")
public record ChatModelFlowResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ModelFlow flow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID modelConfigurationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "False when the model is set but no longer eligible; the task then uses the conversation model")
        boolean available,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "How hard the task's model thinks: the level chosen for the task, else its own default "
                        + "(medium for meeting minutes, off for the others); a model that does not reason ignores it")
        ReasoningEffort reasoningEffort,
        @Schema(description = "The System One connection a classifying task runs on; absent when it runs on a model")
        @Nullable UUID systemOneConnectionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static ChatModelFlowResponse from(ModelCatalogService.FlowView value) {
        return new ChatModelFlowResponse(value.flow(), value.modelConfigurationId(), value.available(),
                value.reasoningEffort(), value.systemOneConnectionId(), value.revision());
    }
}
