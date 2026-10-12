package io.memoryos.api.chat.contract;

import io.memoryos.library.LibraryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ChatLibraryEntryAgent", description = "An assistant the viewer uses that grants them this file")
public record ChatLibraryEntryAgentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name
) {

    public static ChatLibraryEntryAgentResponse from(LibraryEntry.Agent agent) {
        return new ChatLibraryEntryAgentResponse(agent.id(), agent.name());
    }
}
