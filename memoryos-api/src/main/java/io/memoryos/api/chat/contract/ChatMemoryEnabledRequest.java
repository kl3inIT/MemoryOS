package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** {@code Boolean} with {@code @NotNull}: a body without the field is refused instead of switching memory off. */
@Schema(name = "ChatMemoryEnabledInput")
public record ChatMemoryEnabledRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Boolean enabled) {}
