package io.memoryos.ingestion;

public final class ExtractionException extends Exception {

    private final ExtractionFailure failure;

    public ExtractionException(ExtractionFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ExtractionException(ExtractionFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public ExtractionFailure failure() {
        return failure;
    }
}
