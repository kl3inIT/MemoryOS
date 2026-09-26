package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ResetEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                         @Schema(requiredMode = REQUIRED, allowableValues = {"BUFFER_MISSING", "BUFFER_GAP", "BUFFER_EXPIRED"}) String reason) {}
