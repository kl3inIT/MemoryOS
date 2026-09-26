package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@Schema(name = "PersonaSelection")
public record ChatPersonaSelectionRequest(@NotNull UUID personaId) {}
