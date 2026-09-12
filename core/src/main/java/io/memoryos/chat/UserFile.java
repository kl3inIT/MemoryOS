package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;

/** Client-safe metadata; storage keys, upload credentials and plaintext are not history descriptors. */
public record UserFile(UUID id, String filename, String mediaType, long sizeBytes, Status status,
        Instant createdAt, Instant updatedAt, @org.jspecify.annotations.Nullable String errorCode) {
    public enum Status { UPLOADING, PROCESSING, READY, FAILED, DELETING, DELETED }
}
