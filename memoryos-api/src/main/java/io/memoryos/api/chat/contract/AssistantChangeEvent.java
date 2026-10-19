package io.memoryos.api.chat.contract;

import io.memoryos.chat.AssistantPatch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A change the Agent Builder made to the assistant's unsaved form (MEM-280); null fields are unchanged, an empty
 * title, greeting or reasoning level clears it.
 */
public record AssistantChangeEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                   @Schema(requiredMode = REQUIRED) long sequence,
                                   @Schema(requiredMode = REQUIRED) String toolCallId,
                                   @Schema(requiredMode = REQUIRED) AssistantPatch change) {}
