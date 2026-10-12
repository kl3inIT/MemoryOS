package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record OutcomeEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                           @Schema(requiredMode = REQUIRED) long sequence,
                           @Schema(requiredMode = REQUIRED, allowableValues = {"COMPLETED", "CANCELED", "FAILED"}) String status,
                           @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String failureCode,
                           @Schema(requiredMode = REQUIRED) boolean hasArtifacts) {}
