package io.memoryos.retrieval;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public record SearchDocument(UUID documentId, UUID generation, String title, List<SearchPage.Passage> passages,
        int firstOrdinal, int totalChunks, boolean hasMore) {
    @Override public @NonNull String toString() {
        return "SearchDocument[documentId=" + documentId + ", passageCount=" + passages.size() + "]";
    }
}
