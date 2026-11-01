package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;

/** Switches a bot on, so it answers, or off. */
@Schema(name = "UpdateChannelEnabledRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateChannelEnabledRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {}
