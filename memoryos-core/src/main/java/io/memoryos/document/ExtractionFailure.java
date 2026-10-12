package io.memoryos.document;

public enum ExtractionFailure {
    UNSUPPORTED,
    ENCRYPTED,
    MALFORMED,
    CONNECTION_FAILED,
    TIMEOUT,
    WRITE_LIMIT,
    INTERNAL;

    /**
     * Whether the same document may be read once the extraction service is back. Only a service that did not
     * take the request qualifies: every other failure either belongs to the document or may have left remote
     * work running, and asking again would repeat it.
     */
    public boolean retryable() {
        return this == CONNECTION_FAILED;
    }
}
