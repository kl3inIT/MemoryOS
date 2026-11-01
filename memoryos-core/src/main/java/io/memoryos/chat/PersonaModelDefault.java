package io.memoryos.chat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PersonaModelDefault(UUID personaId, @Nullable UUID modelConfigurationId, long revision) {}
