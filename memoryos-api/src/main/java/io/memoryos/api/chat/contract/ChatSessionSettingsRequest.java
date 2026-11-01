package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatSessionSettings")
public record ChatSessionSettingsRequest(@NotNull UUID personaId, @Schema(types = {"string", "null"}, format = "uuid") @Nullable UUID projectId) {}
