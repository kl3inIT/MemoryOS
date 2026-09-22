package io.memoryos.chat;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/**
 * A write refused because the owner's file library is at its limit. The refusal carries the two numbers the
 * person already sees on their storage page, the way {@link ChatFileInUseException} carries what holds a file,
 * so the interface can say how full the library is instead of showing a bare conflict.
 */
public final class ChatStorageFullException extends BusinessException {
    private final long usedBytes;
    private final long limitBytes;

    public ChatStorageFullException(long usedBytes, long limitBytes) {
        super("CHAT_STORAGE_FULL", FailureCategory.CONFLICT, "The file library is full.",
                "chat file library is full: " + usedBytes + " of " + limitBytes + " bytes");
        this.usedBytes = usedBytes;
        this.limitBytes = limitBytes;
    }

    public long usedBytes() { return usedBytes; }

    public long limitBytes() { return limitBytes; }
}
