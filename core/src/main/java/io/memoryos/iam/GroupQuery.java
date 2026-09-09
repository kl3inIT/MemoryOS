package io.memoryos.iam;

import org.jspecify.annotations.Nullable;

public record GroupQuery(@Nullable String search, int page, int size) {
    public static final int MAX_SEARCH_LENGTH = 200;
    public static final int MAX_SIZE = 100;

    public GroupQuery {
        search = normalize(search);
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }

    private static @Nullable String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search must not exceed " + MAX_SEARCH_LENGTH + " characters");
        }
        return normalized;
    }
}
