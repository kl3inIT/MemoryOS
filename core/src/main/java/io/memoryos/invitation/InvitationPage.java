package io.memoryos.invitation;

import java.util.List;
import java.util.Objects;

public record InvitationPage(
        List<InvitationView> items,
        int page,
        int size,
        long totalItems,
        long totalPages
) {

    public InvitationPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (page < 0 || size < 1 || totalItems < 0 || totalPages < 0) {
            throw new IllegalArgumentException("invitation page metadata must not be negative");
        }
    }
}
