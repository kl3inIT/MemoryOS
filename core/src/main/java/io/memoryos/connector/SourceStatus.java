package io.memoryos.connector;

/**
 * Stored pair states plus the derived {@link #PAUSING} summary state: a {@code PAUSED} pair
 * still reports {@code PAUSING} while a worker holds a live lease on a sync or index attempt.
 * An attempt whose lease has lapsed is abandoned rather than running, so the pair reads
 * {@code PAUSED} until a worker reclaims it.
 */
public enum SourceStatus {
    NOT_STARTED,
    INDEXING,
    ACTIVE,
    FAILED,
    PAUSED,
    PAUSING,
    DELETING
}
