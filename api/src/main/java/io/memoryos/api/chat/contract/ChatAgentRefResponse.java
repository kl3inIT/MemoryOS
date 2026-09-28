package io.memoryos.api.chat.contract;

import io.memoryos.chat.AgentRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "AgentRef")
public record ChatAgentRefResponse(UUID id, String name) {
    public static ChatAgentRefResponse from(AgentRef value) {
        return new ChatAgentRefResponse(value.id(), value.name());
    }
}
