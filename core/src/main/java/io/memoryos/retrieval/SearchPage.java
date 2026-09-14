package io.memoryos.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.connector.SourceType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * {@code totalResults} counts readable Documents among the bounded candidates, so it never exceeds what paging can reach.
 * {@code sourceFacets} describes the same candidates before the connector filter.
 */
public record SearchPage(List<Result> results, int page, boolean hasMore, int totalResults, int candidateLimit,
        SourceFacets sourceFacets) {
    @Override public @NonNull String toString() {
        return "SearchPage[resultCount=" + results.size() + ", page=" + page + ", hasMore=" + hasMore
                + ", totalResults=" + totalResults + "]";
    }
    /** {@code sourceTypes} and {@code authors} come only from Source mappings the actor may read. */
    public record Result(UUID documentId, UUID generation, String title, String mediaType,
            Instant updatedAt, double score, List<Section> sections, List<SourceType> sourceTypes, List<String> authors,
            @Nullable String providerUrl) {
        public Result {
            sections = List.copyOf(sections);
            sourceTypes = List.copyOf(sourceTypes);
            authors = List.copyOf(authors);
        }
        public Result(UUID documentId, UUID generation, String title, String mediaType,
                Instant updatedAt, double score, List<Section> sections) {
            this(documentId, generation, title, mediaType, updatedAt, score, sections, List.of(), List.of(), null);
        }
        public Result withOrigins(List<DocumentSourceMetadata> origins) {
            return new Result(documentId, generation, title, mediaType, updatedAt, score, sections,
                    origins.stream().map(DocumentSourceMetadata::type).distinct().toList(),
                    origins.stream().flatMap(origin -> origin.authors().stream()).distinct().limit(5).toList(),
                    DocumentSourceMetadata.providerUrl(origins));
        }
    }
    public record Section(int startOrdinal, int endOrdinal, int matchingOrdinal, double score,
            String content, List<ChunkProvenance> provenance) { }
    public record ChunkProvenance(int ordinal, String provenanceJson) { }
    public record Passage(int ordinal, String content, String provenanceJson) { }
    /** {@code total} counts candidates before the connector filter; a Document mapped to two connectors counts in both types. */
    public record SourceFacets(int total, List<SourceTypeFacet> types) {
        public SourceFacets {
            types = List.copyOf(types);
        }
    }
    public record SourceTypeFacet(SourceType type, int count) { }
}
