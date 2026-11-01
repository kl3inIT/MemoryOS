package io.memoryos.connector;

import java.util.Objects;
import java.util.UUID;

public record CredentialId(UUID value) {
    public CredentialId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
