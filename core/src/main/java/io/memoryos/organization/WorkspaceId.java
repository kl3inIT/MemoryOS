package io.memoryos.organization;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceId(UUID value) {

    public WorkspaceId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
