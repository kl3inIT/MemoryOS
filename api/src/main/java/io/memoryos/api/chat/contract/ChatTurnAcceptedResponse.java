package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "Accepted")
public record ChatTurnAcceptedResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userMessageId,
                                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID assistantMessageId,
                                       @Nullable UUID modelConfigurationId, @Nullable String fallbackReason) {
}
