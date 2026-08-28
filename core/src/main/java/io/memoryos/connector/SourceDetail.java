package io.memoryos.connector;

import java.util.List;

public record SourceDetail(SourceSummary source, List<SourceItemView> items) {
    public SourceDetail {
        items = List.copyOf(items);
    }
}
