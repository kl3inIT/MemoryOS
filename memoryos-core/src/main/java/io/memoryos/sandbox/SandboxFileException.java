package io.memoryos.sandbox;

/**
 * The sandbox answered a file call with a refusal: the path is missing, is a directory, or is not the sandbox user's to
 * read or write. Unlike {@link SandboxException#unavailable}, the sandbox is up; the message is one fixed sentence.
 */
public final class SandboxFileException extends RuntimeException {
    public SandboxFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
