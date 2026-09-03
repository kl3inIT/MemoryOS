package io.memoryos.objectstorage;

import java.util.Objects;
import java.util.UUID;

public record ObjectVerificationToken(UUID value) {
    public ObjectVerificationToken {
        Objects.requireNonNull(value, "value must not be null");
    }
}
