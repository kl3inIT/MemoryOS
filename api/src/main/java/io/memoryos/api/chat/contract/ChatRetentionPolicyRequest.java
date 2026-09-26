package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatRetentionInput")
public record ChatRetentionPolicyRequest(@Schema(description = "Days of inactivity, 1 to 3650; leave it out to keep conversations until you delete them")
        @Min(1) @Max(3650) @Nullable Integer days) {}
