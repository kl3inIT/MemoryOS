package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The provider accepted the endpoint and key. {@code modelCount} is null when the adapter cannot list models;
 * {@code latencyMillis} is the round trip of the check, as Northstar's gateway test reports it.
 */
@Schema(name = "ProviderTestResult")
public record ChatProviderTestResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Integer modelCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long latencyMillis
) {
    public static ChatProviderTestResponse of(int count, long latencyMillis) {
        return new ChatProviderTestResponse(count < 0 ? null : count, latencyMillis);
    }
}
