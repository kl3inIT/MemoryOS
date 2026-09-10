package io.memoryos.connector;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Dates belong to this source item; FILE dates describe upload, not extraction or reindex. */
public record DocumentSourceMetadata(UUID sourceId, UUID itemId, SourceType type,
        @Nullable Instant createdAt, @Nullable Instant updatedAt, List<String> authors) {
    public DocumentSourceMetadata { authors = List.copyOf(authors); }
}
