package io.memoryos.iam;

import java.util.List;
import java.util.Objects;

public record UserPage(
        List<UserListItem> items,
        int page,
        int size,
        long totalItems,
        long totalPages,
        UserCounts counts
) {

    public UserPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(counts, "counts must not be null");
        if (page < 0 || size < 1 || totalItems < 0 || totalPages < 0) {
            throw new IllegalArgumentException("user page metadata is invalid");
        }
        if (items.size() > size) {
            throw new IllegalArgumentException("user page contains more items than its size");
        }
    }
}
