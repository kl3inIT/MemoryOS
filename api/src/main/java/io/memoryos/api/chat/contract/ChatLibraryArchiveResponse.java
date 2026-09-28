package io.memoryos.api.chat.contract;

import io.memoryos.library.LibraryArchive;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibraryArchive")
public record ChatLibraryArchiveResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PENDING", "RUNNING", "READY", "FAILED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int fileCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, format = "int64") @Nullable Long sizeBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Files that were no longer available when packing") List<String> skipped,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String failure,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") @Nullable Instant expiresAt
) {

    public static ChatLibraryArchiveResponse from(LibraryArchive archive) {
        return new ChatLibraryArchiveResponse(archive.id(), archive.status().name(), archive.fileCount(), archive.sizeBytes(),
                archive.skipped(), archive.failure(), archive.createdAt(), archive.expiresAt());
    }
}
