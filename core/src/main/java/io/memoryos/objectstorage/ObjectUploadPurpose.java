package io.memoryos.objectstorage;

/** Server-selected consumer; never inferred from client content type or filename. */
public enum ObjectUploadPurpose {
    BINARY(10L * 1024 * 1024),
    CHAT_FILE(250L * 1024 * 1024);

    private final long maximumBytes;

    ObjectUploadPurpose(long maximumBytes) { this.maximumBytes = maximumBytes; }

    public long maximumBytes() { return maximumBytes; }
}
