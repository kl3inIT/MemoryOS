package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;

/** Makes a bot answer anyone who addresses it, from its assistant's attachments only, or only the Tenant's people. */
@Schema(name = "UpdateChannelPublicRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateChannelPublicRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean publicAccess) {}
