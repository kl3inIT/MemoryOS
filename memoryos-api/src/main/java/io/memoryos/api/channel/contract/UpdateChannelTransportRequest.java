package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelDelivery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Changes how a bot whose platform offers both receives: pushed to the public address, or asked for. */
@Schema(name = "UpdateChannelTransportRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateChannelTransportRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ChannelDelivery transport) {}
