package io.memoryos.iam;

import java.util.List;

public record GroupMemberPage(
        List<GroupMember> items,
        int page,
        int size,
        long totalItems,
        long totalPages
) {
    public GroupMemberPage {
        items = List.copyOf(items);
        if (page < 0 || size < 1 || totalItems < 0 || totalPages < 0 || items.size() > size) {
            throw new IllegalArgumentException("group member page metadata is invalid");
        }
    }
}
