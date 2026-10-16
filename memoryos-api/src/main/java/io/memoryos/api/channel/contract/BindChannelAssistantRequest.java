package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The assistant a bot answers with; absent or null to answer with the built-in one. */
@Schema(name = "BindChannelAssistantRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BindChannelAssistantRequest(
        @Schema(types = {"string", "null"}, format = "uuid",
                description = "The assistant; null answers with the built-in one, and a bot bound elsewhere moves")
        @Nullable UUID personaId) {}
