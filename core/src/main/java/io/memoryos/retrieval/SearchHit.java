package io.memoryos.retrieval;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import io.memoryos.connector.DocumentSourceMetadata;

public record SearchHit(UUID documentId, UUID generation, int ordinal, String title, String mediaType,
        String content, String provenanceJson, Instant updatedAt, double score, List<DocumentSourceMetadata> origins) {
    public SearchHit { origins = List.copyOf(origins); }
    public SearchHit(UUID documentId, UUID generation, int ordinal, String title, String mediaType,
            String content, String provenanceJson, Instant updatedAt, double score) {
        this(documentId, generation, ordinal, title, mediaType, content, provenanceJson, updatedAt, score, List.of());
    }
    public SearchHit withScore(double value) {
        return new SearchHit(documentId, generation, ordinal, title, mediaType, content, provenanceJson, updatedAt, value, origins);
    }
    public SearchHit withOrigins(List<DocumentSourceMetadata> value) {
        return new SearchHit(documentId, generation, ordinal, title, mediaType, content, provenanceJson, updatedAt, score, value);
    }
}
