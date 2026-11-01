package io.memoryos.iam;

import java.util.List;

public record GroupIdentityPage(
        List<GroupIdentity> items,
        int page,
        int size,
        long totalItems,
        long totalPages
) {
    public GroupIdentityPage {
        items = List.copyOf(items);
        if (page < 0 || size < 1 || totalItems < 0 || totalPages < 0 || items.size() > size) {
            throw new IllegalArgumentException("group identity page metadata is invalid");
        }
    }
}
