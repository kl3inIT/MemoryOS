package io.memoryos.objectstorage;

/** Server-selected consumer; never inferred from client content type or filename. */
public enum ObjectUploadPurpose {
    BINARY(ObjectUploadSpecification.MAX_SIZE_BYTES),
    CHAT_FILE(250L * 1024 * 1024),
    /** A meeting recording, held only until it has been transcribed. Soniox accepts five hours of audio. */
    MEETING_AUDIO(500L * 1024 * 1024);

    private final long maximumBytes;

    ObjectUploadPurpose(long maximumBytes) { this.maximumBytes = maximumBytes; }

    public long maximumBytes() { return maximumBytes; }
}
