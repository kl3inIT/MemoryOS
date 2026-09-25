package io.memoryos.document;

public enum ExtractionFailure {
    UNSUPPORTED,
    ENCRYPTED,
    MALFORMED,
    CONNECTION_FAILED,
    TIMEOUT,
    WRITE_LIMIT,
    INTERNAL
}
