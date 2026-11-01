package io.memoryos.api.chat.contract;

import io.memoryos.ai.ModelDefault;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "Default")
public record ChatModelDefaultResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") @Nullable UUID modelConfigurationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static ChatModelDefaultResponse from(ModelDefault value) {
        return new ChatModelDefaultResponse(value.modelConfigurationId(), value.revision());
    }
}
