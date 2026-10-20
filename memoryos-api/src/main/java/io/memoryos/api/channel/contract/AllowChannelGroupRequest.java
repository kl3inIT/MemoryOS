package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;

/** Whether the bot may answer in a group chat that needs an administrator's permission (MEM-285). */
@Schema(name = "AllowChannelGroupRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AllowChannelGroupRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "True allows the bot to answer there, false withdraws it")
        boolean allowed) {}
