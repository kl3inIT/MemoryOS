package io.memoryos.sandbox;

/** The runtime no longer has the sandbox: it expired, was killed or never existed. {@link SandboxService} replaces it. */
public final class SandboxGoneException extends RuntimeException {
    public SandboxGoneException(String id, Throwable cause) {
        super("Sandbox " + id + " is gone", cause);
    }
}
