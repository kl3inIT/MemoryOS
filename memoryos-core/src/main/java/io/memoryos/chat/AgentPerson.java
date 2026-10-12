package io.memoryos.chat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AgentPerson(UUID actorId, @Nullable String name, @Nullable String email) {}
