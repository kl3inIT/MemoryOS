package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The bot a member wants a linking code for. */
@Schema(name = "CreateChannelLinkCodeRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateChannelLinkCodeRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID connectionId) {}
