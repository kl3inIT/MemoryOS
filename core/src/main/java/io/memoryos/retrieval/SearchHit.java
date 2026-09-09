package io.memoryos.retrieval;

import java.time.Instant;
import java.util.UUID;

public record SearchHit(UUID documentId, UUID generation, int ordinal, String title, String mediaType,
        String content, String provenanceJson, Instant updatedAt, double score) { }
