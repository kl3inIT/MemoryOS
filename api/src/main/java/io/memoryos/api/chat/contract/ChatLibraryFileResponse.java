package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatLibraryFile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibraryFile")
public record ChatLibraryFileResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"UPLOAD", "GENERATED", "IMAGE"}) String source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"DOCUMENT", "SPREADSHEET", "IMAGE", "PRESENTATION", "OTHER"}) String category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid",
                description = "The conversation that produced the file; null for an upload") @Nullable UUID sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String sessionTitle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid",
                description = "The answer that produced an artifact, or the first message in the filtered conversation"
                        + " that attached an upload; null for an upload listed without a conversation")
        @Nullable UUID messageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Starred by its owner") boolean favorite,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"UPLOADING", "PROCESSING", "READY", "FAILED"},
                description = "READY unless listed with status=PENDING, which shows uploads still in progress or failed")
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Why a FAILED upload failed") @Nullable String errorCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time",
                description = "When the owner deleted it; set only in the trash") @Nullable Instant deletedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time",
                description = "When its bytes may be released") @Nullable Instant purgeAfter,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Projects and assistants holding this file") List<ChatLibraryFileUsageResponse> usedBy,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "False while a project or assistant holds the file") boolean deletable
) {

    public static ChatLibraryFileResponse from(ChatLibraryFile file) {
        return new ChatLibraryFileResponse(file.source().name(), file.id(), file.filename(), file.mediaType(),
                file.sizeBytes(), file.createdAt(), file.category().name(), file.sessionId(), file.sessionTitle(),
                file.messageId(), file.favorite(), file.status().name(), file.errorCode(), file.deletedAt(),
                file.purgeAfter(), file.usedBy().stream().map(ChatLibraryFileUsageResponse::from).toList(), file.deletable());
    }
}
