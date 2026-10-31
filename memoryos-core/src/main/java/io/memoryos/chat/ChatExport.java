package io.memoryos.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ChatExport(UUID id, ChatExportStatus status, @Nullable Integer sessionCount, @Nullable Integer fileCount,
                         List<String> skipped, @Nullable Long sizeBytes, @Nullable String failure,
                         Instant createdAt, @Nullable Instant expiresAt) {}
