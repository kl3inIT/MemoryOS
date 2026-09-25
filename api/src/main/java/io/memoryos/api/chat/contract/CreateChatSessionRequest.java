package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "CreateChatSession")
public record CreateChatSessionRequest(@NotBlank @Size(max = 200) String title, @Nullable UUID personaId,
        @Nullable UUID projectId,
        @Schema(description = "Leave no history: listed nowhere, deleted with its uploads after its window")
        @Nullable Boolean temporary) {}
