package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The assistant a bot answers with. */
@Schema(name = "ChannelAssistantResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelAssistantResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}
