package io.memoryos.iam;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

public record GroupIdentity(GroupId id, String name, @Nullable GroupSystemKey systemKey) {
    public GroupIdentity {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
