package io.memoryos.connector;

/**
 * Stored pair states plus the derived {@link #PAUSING} summary state: a {@code PAUSED} pair
 * still reports {@code PAUSING} while in-flight sync or index attempts keep running.
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
