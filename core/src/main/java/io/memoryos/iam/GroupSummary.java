package io.memoryos.iam;

import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

public record GroupSummary(
        GroupId id,
        String name,
        @Nullable GroupSystemKey systemKey,
        long memberCount,
        long managerCount,
        Set<IamCapability> capabilities,
        Set<GroupAction> actions
) {
    public GroupSummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (managerCount < 0 || managerCount > memberCount) {
            throw new IllegalArgumentException("group member counts are invalid");
        }
        capabilities = Set.copyOf(capabilities);
        actions = Set.copyOf(actions);
    }
}
