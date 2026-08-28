package io.memoryos.ingestion;

public enum ExtractionFailure {
    UNSUPPORTED,
    ENCRYPTED,
    MALFORMED,
    TIMEOUT,
    WRITE_LIMIT,
    INTERNAL
}
