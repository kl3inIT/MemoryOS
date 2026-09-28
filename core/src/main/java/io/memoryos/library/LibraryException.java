package io.memoryos.library;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/**
 * A file library failure. The codes keep the {@code CHAT_} prefix they had while the library lived in Chat: they are
 * part of the HTTP problem contract the web client reads.
 */
public final class LibraryException extends BusinessException {
    private LibraryException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static LibraryException unavailable() {
        return new LibraryException("CHAT_UNAVAILABLE", FailureCategory.NOT_FOUND, "Chat is unavailable.");
    }

    public static LibraryException invalid(String message) {
        return new LibraryException("CHAT_INVALID_REQUEST", FailureCategory.VALIDATION, message);
    }

    public static LibraryException conflict() {
        return new LibraryException("CHAT_CONFLICT", FailureCategory.CONFLICT, "Chat changed or has an active reply.");
    }

    /** The owner's file library is at its storage limit; the caller's own numbers say by how much. */
    public static LibraryException storageFull(long usedBytes, long limitBytes) {
        return new LibraryException("CHAT_STORAGE_FULL", FailureCategory.CONFLICT,
                "The file library is full: " + usedBytes + " of " + limitBytes + " bytes are used.");
    }
}
