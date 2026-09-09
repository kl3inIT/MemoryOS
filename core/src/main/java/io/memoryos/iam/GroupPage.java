package io.memoryos.iam;

import java.util.List;

public record GroupPage(
        List<GroupSummary> items,
        int page,
        int size,
        long totalItems,
        long totalPages
) {
    public GroupPage {
        items = List.copyOf(items);
        if (page < 0 || size < 1 || totalItems < 0 || totalPages < 0 || items.size() > size) {
            throw new IllegalArgumentException("group page metadata is invalid");
        }
    }
}
