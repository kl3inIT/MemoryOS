package io.memoryos.connector;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One keyset page of the readable Source documents. {@code query} (empty for any) matches the filename or title;
 * empty {@code sourceIds} and {@code categories} admit every Source and category. {@code byName} orders by
 * lowercase filename ascending, otherwise newest first; both break ties by Document id. {@code after} is the last
 * entry of the previous page.
 */
public record SourceDocumentBrowse(String query, Set<UUID> sourceIds, Set<String> categories, boolean byName,
        @Nullable After after, int limit) {
    public SourceDocumentBrowse {
        if (query.length() > 200 || sourceIds.size() > 500 || categories.size() > 5 || limit < 1 || limit > 101)
            throw new IllegalArgumentException("source document browse out of bounds");
        sourceIds = Set.copyOf(sourceIds);
        categories = Set.copyOf(categories);
    }

    /** The sort keys of the last entry already returned. */
    public record After(Instant updatedAt, String filename, UUID documentId) {}
}
