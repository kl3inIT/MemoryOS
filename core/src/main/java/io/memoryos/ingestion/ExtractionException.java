package io.memoryos.ingestion;

import java.util.Objects;

public final class ExtractionException extends Exception {

    private final ExtractionFailure failure;

    public ExtractionException(ExtractionFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public ExtractionException(ExtractionFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public ExtractionFailure failure() {
        return failure;
    }
}
