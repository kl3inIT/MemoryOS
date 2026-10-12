package io.memoryos.connector;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Dates belong to this source item; FILE dates describe upload, not extraction or reindex.
 * {@code providerUrl} is where a reader opens the item in its provider, as the provider's adapter builds it; it is
 * presentation only, not indexed, and grants no access: the provider enforces its own permissions when it is opened.
 */
public record DocumentSourceMetadata(UUID sourceId, UUID itemId, SourceType type,
        @Nullable Instant createdAt, @Nullable Instant updatedAt, List<String> authors, @Nullable String providerUrl) {
    public DocumentSourceMetadata { authors = List.copyOf(authors); }

    public DocumentSourceMetadata(UUID sourceId, UUID itemId, SourceType type,
            @Nullable Instant createdAt, @Nullable Instant updatedAt, List<String> authors) {
        this(sourceId, itemId, type, createdAt, updatedAt, authors, null);
    }

    /** The first origin's provider link, for a Document cited once whatever Sources map it. */
    public static @Nullable String providerUrl(List<DocumentSourceMetadata> origins) {
        return origins.stream().map(DocumentSourceMetadata::providerUrl).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
