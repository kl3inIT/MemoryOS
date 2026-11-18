package io.memoryos.connector;

import java.util.List;
import java.util.Objects;

public record SourcePage(
        List<SourceSummary> items,
        int page,
        int size,
        long totalItems,
        long totalPages,
        SourceCounts counts
) {

    public SourcePage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(counts, "counts must not be null");
        if (page < 0 || size < 1 || totalItems < 0 || totalPages < 0) {
            throw new IllegalArgumentException("source page metadata is invalid");
        }
        if (items.size() > size) {
            throw new IllegalArgumentException("source page contains more items than its size");
        }
    }
}
