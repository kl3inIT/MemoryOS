package io.memoryos.api.chat.contract;

import io.memoryos.library.UserFile;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ChatFileResponse(UUID id, String filename, String mediaType, long sizeBytes, UserFile.Status status,
        Instant createdAt, Instant updatedAt, @Nullable String errorCode, boolean searchReady) {
    public static ChatFileResponse from(UserFile file) {
        return from(file, false);
    }
    public static ChatFileResponse from(UserFile file, boolean searchReady) {
        return new ChatFileResponse(file.id(), file.filename(), file.mediaType(), file.sizeBytes(), file.status(), file.createdAt(), file.updatedAt(), file.errorCode(), searchReady);
    }
}
