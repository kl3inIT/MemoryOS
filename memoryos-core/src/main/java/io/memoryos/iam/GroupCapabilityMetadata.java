package io.memoryos.iam;

import java.util.Objects;
import java.util.Set;

public record GroupCapabilityMetadata(
        IamCapability id,
        String label,
        String description,
        boolean editable,
        Set<IamCapability> implies
) {
    public GroupCapabilityMetadata {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(description, "description must not be null");
        implies = Set.copyOf(implies);
    }
}
