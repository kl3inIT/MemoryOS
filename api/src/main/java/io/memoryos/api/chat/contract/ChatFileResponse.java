package io.memoryos.api.chat.contract;

import io.memoryos.chat.UserFile;
import java.time.Instant;
import java.util.UUID;

public record ChatFileResponse(UUID id, String filename, String mediaType, long sizeBytes, UserFile.Status status,
        Instant createdAt, Instant updatedAt, @org.jspecify.annotations.Nullable String errorCode, boolean searchReady) {
    public static ChatFileResponse from(UserFile file) {
        return from(file, false);
    }
    public static ChatFileResponse from(UserFile file, boolean searchReady) {
        return new ChatFileResponse(file.id(), file.filename(), file.mediaType(), file.sizeBytes(), file.status(), file.createdAt(), file.updatedAt(), file.errorCode(), searchReady);
    }
}
