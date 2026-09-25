package io.memoryos.api.chat.contract;

import io.memoryos.chat.AgentPerson;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "AgentPerson")
public record ChatAgentPersonResponse(UUID actorId, @Nullable String name, @Nullable String email) {
    public static ChatAgentPersonResponse from(AgentPerson value) {
        return new ChatAgentPersonResponse(value.actorId(), value.name(), value.email());
    }
}
