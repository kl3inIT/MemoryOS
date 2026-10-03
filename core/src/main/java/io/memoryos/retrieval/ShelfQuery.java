package io.memoryos.retrieval;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A page of the reader's Source documents: {@code query} matches the filename or title (blank for any), empty
 * {@code sourceIds} and {@code categories} admit all, and {@code cursor} is the previous page's
 * {@link ShelfPage#nextCursor()} for the same filters and sort.
 */
public record ShelfQuery(String query, Set<UUID> sourceIds, Set<String> categories, Sort sort, @Nullable String cursor, int limit) {
    public ShelfQuery {
        query = query == null ? "" : query;
        sourceIds = Set.copyOf(sourceIds);
        categories = Set.copyOf(categories);
    }

    /** NEWEST: last updated first; NAME: filename A to Z. Both break ties by Document id. */
    public enum Sort { NEWEST, NAME }
}
