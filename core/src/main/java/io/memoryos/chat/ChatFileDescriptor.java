package io.memoryos.chat;

import java.util.UUID;

/** Immutable display metadata, never an authorization or a storage URL. */
public record ChatFileDescriptor(UUID id, String filename, String mediaType, long sizeBytes) {
    public static ChatFileDescriptor from(UserFile file) {
        return new ChatFileDescriptor(file.id(), file.filename(), file.mediaType(), file.sizeBytes());
    }
}
