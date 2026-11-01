package io.memoryos.retrieval;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** One window of a document's passages; {@code mediaType} is the original's, so a reader can show it. */
public record SearchDocument(UUID documentId, UUID generation, String title, String mediaType,
        List<SearchPage.Passage> passages, int firstOrdinal, int totalChunks, boolean hasMore) {
    @Override public @NonNull String toString() {
        return "SearchDocument[documentId=" + documentId + ", passageCount=" + passages.size() + "]";
    }
}
