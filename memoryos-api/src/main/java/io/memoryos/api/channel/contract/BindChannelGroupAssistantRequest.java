package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The assistant a group chat answers with in place of its bot's default; absent or null to use the default. */
@Schema(name = "BindChannelGroupAssistantRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BindChannelGroupAssistantRequest(
        @Schema(types = {"string", "null"}, format = "uuid",
                description = "The assistant, the built-in one included; null answers with the bot's default")
        @Nullable UUID personaId) {}
