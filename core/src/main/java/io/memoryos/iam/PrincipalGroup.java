package io.memoryos.iam;

import java.util.Objects;

/** An ordinary (non-system) Group as a sharing picker shows it. */
public record PrincipalGroup(GroupId id, String name) {
    public PrincipalGroup {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
