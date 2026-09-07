package io.memoryos.iam;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

public record UserQuery(
        @Nullable String search,
        @Nullable UserStatus status,
        @Nullable TenantMembershipRole role,
        @Nullable GroupId groupId,
        UserSort sort,
        int page,
        int size
) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_SEARCH_LENGTH = 200;
    public static final UserSort DEFAULT_SORT = UserSort.NAME_ASC;

    public UserQuery {
        search = normalizeSearch(search);
        sort = sort == null ? DEFAULT_SORT : sort;
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
    }

    public static UserQuery defaults() {
        return new UserQuery(null, null, null, null, DEFAULT_SORT, DEFAULT_PAGE, DEFAULT_SIZE);
    }

    public long offset() {
        return Math.multiplyExact((long) page, size);
    }

    private static @Nullable String normalizeSearch(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search must not exceed " + MAX_SEARCH_LENGTH + " characters");
        }
        return normalized;
    }
}
