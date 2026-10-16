package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(name = "PersonaSelection")
public record ChatPersonaSelectionRequest(@NotNull UUID personaId) {}
