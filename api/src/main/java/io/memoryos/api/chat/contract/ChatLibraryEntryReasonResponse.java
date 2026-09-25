package io.memoryos.api.chat.contract;

import io.memoryos.library.LibraryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatLibraryEntryReason", description = "Why the row is visible: the rule that admitted it")
public record ChatLibraryEntryReasonResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"OWNER", "MEMBER_SHARE", "GROUP_SHARE",
                "AGENT", "PUBLIC_SOURCE", "GROUP_SOURCE", "PROVIDER_SOURCE"}) String kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The viewer's Groups for GROUP_SHARE and GROUP_SOURCE, the assistants for AGENT; empty"
                        + " otherwise, where the owner or the Source is named on the row itself")
        List<String> names
) {

    public static ChatLibraryEntryReasonResponse from(LibraryEntry.Reason reason) {
        return new ChatLibraryEntryReasonResponse(reason.kind().name(), reason.names());
    }
}
