package io.memoryos.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public record SearchPage(List<Result> results, int page, boolean hasMore, int candidateLimit) {
    @Override public @NonNull String toString() {
        return "SearchPage[resultCount=" + results.size() + ", page=" + page + ", hasMore=" + hasMore + "]";
    }
    public record Result(UUID documentId, UUID generation, String title, String mediaType,
            Instant updatedAt, double score, List<Section> sections) { }
    public record Section(int startOrdinal, int endOrdinal, int matchingOrdinal, double score,
            String content, List<ChunkProvenance> provenance) { }
    public record ChunkProvenance(int ordinal, String provenanceJson) { }
    public record Passage(int ordinal, String content, String provenanceJson) { }
}
