package io.memoryos.invitation;

import java.util.Locale;
import java.util.Objects;

public record InvitationQuery(
        InvitationStatus status,
        String email,
        InvitationSort sort,
        int page,
        int size
) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final InvitationSort DEFAULT_SORT = InvitationSort.CREATED_AT_DESC;

    public InvitationQuery {
        Objects.requireNonNull(sort, "sort must not be null");
        email = normalizeEmailFilter(email);
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }

    public static InvitationQuery defaults() {
        return new InvitationQuery(null, null, DEFAULT_SORT, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public long offset() {
        return Math.multiplyExact((long) page, size);
    }

    private static String normalizeEmailFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254) {
            throw new IllegalArgumentException("email filter must not exceed 254 characters");
        }
        return normalized;
    }
}
