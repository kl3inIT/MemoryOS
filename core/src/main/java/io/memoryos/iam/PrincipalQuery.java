package io.memoryos.iam;

import org.jspecify.annotations.Nullable;

/** A typeahead search for the active members and ordinary Groups of the searcher's Tenant; at most {@code size} of each. */
public record PrincipalQuery(@Nullable String search, int size) {
    public static final int MAX_SEARCH_LENGTH = 200;
    public static final int MAX_SIZE = 50;

    public PrincipalQuery {
        search = search == null || search.isBlank() ? null : search.strip();
        if (search != null && search.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search must not exceed " + MAX_SEARCH_LENGTH + " characters");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }
}
