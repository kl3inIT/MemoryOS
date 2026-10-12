package io.memoryos.objectstorage;

import java.util.Objects;

public record ObjectMetadata(long sizeBytes, String mediaType, ContentSha256 checksum) {
    public ObjectMetadata {
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        if (mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        Objects.requireNonNull(checksum, "checksum must not be null");
    }
}
