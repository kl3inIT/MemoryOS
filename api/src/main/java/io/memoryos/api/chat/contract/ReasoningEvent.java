package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ReasoningEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                             @Schema(requiredMode = REQUIRED) long sequence,
                             @Schema(requiredMode = REQUIRED) String text,
                             @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String parentToolCallId) {}
