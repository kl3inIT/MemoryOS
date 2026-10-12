package io.memoryos.chat;

import org.jspecify.annotations.Nullable;

public record AgentOwner(@Nullable AgentPerson actor, @Nullable AgentRef group) {}
