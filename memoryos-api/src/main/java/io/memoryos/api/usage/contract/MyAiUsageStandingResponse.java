package io.memoryos.api.usage.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The caller's budget, or none. Always a JSON body: an empty 200 reads as an empty object in the browser client, which
 * the usage page took for a budget and failed on.
 */
@Schema(name = "MyAiUsageStanding")
public record MyAiUsageStandingResponse(
        @Schema(description = "Absent when no enabled budget binds the caller") @Nullable AiUsageStandingResponse standing) {}
