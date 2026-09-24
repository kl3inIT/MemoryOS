package io.memoryos.chat.catalog;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PersonaModelDefault(UUID personaId, @Nullable UUID modelConfigurationId, long revision) {}
