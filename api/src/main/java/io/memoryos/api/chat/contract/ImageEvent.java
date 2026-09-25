package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatImageEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ImageEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                         @Schema(requiredMode = REQUIRED) long sequence,
                         @Schema(requiredMode = REQUIRED) String toolCallId,
                         @Schema(requiredMode = REQUIRED) ChatImageEvent.Stage stage,
                         @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable UUID id,
                         @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String mediaType,
                         @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String revisedPrompt) {}
