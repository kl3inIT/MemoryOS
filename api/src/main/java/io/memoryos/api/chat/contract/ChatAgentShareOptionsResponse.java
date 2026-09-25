package io.memoryos.api.chat.contract;

import io.memoryos.chat.AgentShareOptions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "AgentShareOptions")
public record ChatAgentShareOptionsResponse(List<ChatAgentPersonResponse> people, List<ChatAgentRefResponse> groups) {
    public static ChatAgentShareOptionsResponse from(AgentShareOptions value) {
        return new ChatAgentShareOptionsResponse(value.people().stream().map(ChatAgentPersonResponse::from).toList(),
                value.groups().stream().map(ChatAgentRefResponse::from).toList());
    }
}
