package io.memoryos.connector;

import org.jspecify.annotations.Nullable;

/** One page of the administration Source list: the filters, and the page to read. */
public record SourcePageQuery(
        @Nullable String search,
        @Nullable SourceStatus status,
        @Nullable SourceType type,
        @Nullable SourceAccess access,
        int page,
        int size
) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_SEARCH_LENGTH = 200;

    public SourcePageQuery {
        search = normalizeSearch(search);
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }

    public long offset() {
        return Math.multiplyExact((long) page, size);
    }

    private static @Nullable String normalizeSearch(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search must not exceed " + MAX_SEARCH_LENGTH + " characters");
        }
        return normalized;
    }
}
