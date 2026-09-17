package io.memoryos.ingestion;

public enum ExtractionFailure {
    UNSUPPORTED,
    ENCRYPTED,
    MALFORMED,
    CONNECTION_FAILED,
    TIMEOUT,
    WRITE_LIMIT,
    INTERNAL
}
