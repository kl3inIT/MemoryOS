package io.memoryos.connector;

/**
 * {@code SUPERSEDED}: newer work replaced the operation. {@code CANCELLED}: something else stopped it — its Source
 * was paused or is being deleted, or a selection's credential changed — and {@code errorCode} says which.
 */
public enum SourceOperationStatus {
    NOT_STARTED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    SUPERSEDED,
    CANCELLED
}
