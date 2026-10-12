package io.memoryos.api.chat.contract;

import io.memoryos.library.LibraryEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibraryEntry",
        description = "One row of a library view: an owned file, or a read-only reference to something the viewer may"
                + " read now through a share, an assistant or a Source")
public record ChatLibraryEntryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"UPLOAD", "GENERATED", "IMAGE", "MEETING", "AGENT_FILE", "DOCUMENT"}) String kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Null for a meeting") @Nullable String mediaType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, format = "int64",
                description = "Null for a meeting") @Nullable Long sizeBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                allowableValues = {"DOCUMENT", "SPREADSHEET", "IMAGE", "PRESENTATION", "OTHER"},
                description = "Null for a meeting") @Nullable String category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "When the file or meeting was created, or when a Source document last changed")
        Instant at,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The viewer owns it; only owned rows are renamed, trashed or counted toward storage")
        boolean owned,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Starred by the viewer") boolean starred,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time",
                description = "When the viewer last opened it") @Nullable Instant openedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Who owns a meeting shared with the viewer or uploaded an assistant's file")
        @Nullable String ownerName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatLibraryEntryReasonResponse reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid",
                description = "The conversation that produced a generated file or image") @Nullable UUID sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String sessionTitle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid",
                description = "The answer that produced a generated file or image") @Nullable UUID messageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Set for a meeting") @Nullable ChatLibraryEntryMeetingResponse meeting,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The assistants granting an assistant's file; empty for every other kind")
        List<ChatLibraryEntryAgentResponse> agents,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Set for a Source document") @Nullable ChatLibraryEntryDocumentResponse document
) {

    public static ChatLibraryEntryResponse from(LibraryEntry entry) {
        var meeting = entry.meeting();
        var document = entry.document();
        var category = entry.category();
        return new ChatLibraryEntryResponse(entry.kind().name(), entry.id(), entry.name(), entry.mediaType(),
                entry.sizeBytes(), category == null ? null : category.name(), entry.at(), entry.owned(),
                entry.starred(), entry.openedAt(), entry.ownerName(),
                ChatLibraryEntryReasonResponse.from(entry.reason()), entry.sessionId(), entry.sessionTitle(),
                entry.messageId(), meeting == null ? null : ChatLibraryEntryMeetingResponse.from(meeting),
                entry.agents().stream().map(ChatLibraryEntryAgentResponse::from).toList(),
                document == null ? null : ChatLibraryEntryDocumentResponse.from(document));
    }
}
