package io.memoryos.connector;

/** What the actor may read, whatever the page's search and filters: Sources by status, their documents, busy or not. */
public record SourceCounts(
        long total,
        long notStarted,
        long indexing,
        long active,
        long failed,
        long paused,
        long pausing,
        long deleting,
        long documents,
        boolean pendingWork
) {

    public SourceCounts {
        if (total < 0 || notStarted < 0 || indexing < 0 || active < 0 || failed < 0 || paused < 0
                || pausing < 0 || deleting < 0 || documents < 0) {
            throw new IllegalArgumentException("source counts must not be negative");
        }
    }
}
