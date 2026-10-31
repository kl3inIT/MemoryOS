package io.memoryos.connector;

public interface SourceRunHistoryMaintenance {
    int pruneHistory(int limit);
}
