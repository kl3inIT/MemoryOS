package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "Cancellation")
public record ChatTurnCancellationResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID assistantMessageId,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"RUNNING", "COMPLETED", "CANCELED", "FAILED"}) String status) {
}
