package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record TopLevelBranchingEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                     @Schema(requiredMode = REQUIRED) long sequence,
                                     @Schema(requiredMode = REQUIRED) int branches) {}
