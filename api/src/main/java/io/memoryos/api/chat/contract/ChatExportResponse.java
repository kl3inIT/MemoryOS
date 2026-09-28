package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatExport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatExport")
public record ChatExportResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "PENDING, RUNNING, READY or FAILED") String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"})
        @Nullable Integer sessionCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"})
        @Nullable Integer fileCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Names of the files the export left out") List<String> skipped,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"})
        @Nullable Long sizeBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String failure,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time")
        @Nullable Instant expiresAt
) {

    public static ChatExportResponse from(ChatExport export) {
        return new ChatExportResponse(export.id(), export.status().name(), export.sessionCount(),
                export.fileCount(), export.skipped(), export.sizeBytes(), export.failure(), export.createdAt(),
                export.expiresAt());
    }
}
