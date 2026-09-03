package io.memoryos.objectstorage;

import java.util.Objects;

public record ObjectUploadSpecification(
        String filename,
        String mediaType,
        long sizeBytes,
        ContentSha256 checksum
) {
    public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    public ObjectUploadSpecification {
        Objects.requireNonNull(filename, "filename must not be null");
        filename = filename.trim();
        if (filename.isEmpty() || filename.length() > 255) {
            throw new IllegalArgumentException("filename must contain between 1 and 255 characters");
        }
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        mediaType = mediaType.trim();
        if (mediaType.isEmpty() || mediaType.length() > 160) {
            throw new IllegalArgumentException("mediaType must contain between 1 and 160 characters");
        }
        if (sizeBytes < 1 || sizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("sizeBytes must be between 1 and 10485760");
        }
        Objects.requireNonNull(checksum, "checksum must not be null");
    }

    public UploadConstraints constraints() {
        return new UploadConstraints(sizeBytes, mediaType, checksum);
    }
}
