package io.memoryos.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record LibraryArchive(UUID id, LibraryArchiveStatus status, int fileCount, @Nullable Long sizeBytes, List<String> skipped,
                                 @Nullable String failure, Instant createdAt, @Nullable Instant expiresAt) {}
