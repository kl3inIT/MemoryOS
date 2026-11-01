package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

/** A new App Secret of the bot's app, checked with the platform before it replaces the old one. */
@Schema(name = "ReplaceChannelSecretRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReplaceChannelSecretRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 256) String appSecret) {

    @Override public @NonNull String toString() { return "ReplaceChannelSecretRequest[redacted]"; }
}
