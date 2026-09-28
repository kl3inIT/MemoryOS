package io.memoryos.library;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Client-safe metadata; storage keys, upload credentials and plaintext are not history descriptors. */
public record UserFile(UUID id, String filename, String mediaType, long sizeBytes, Status status,
        Instant createdAt, Instant updatedAt, @Nullable String errorCode) {
    public enum Status { UPLOADING, PROCESSING, READY, FAILED, DELETING, DELETED }
}
