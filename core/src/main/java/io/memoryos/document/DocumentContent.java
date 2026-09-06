package io.memoryos.document;

import java.util.Map;
import java.util.UUID;

public record DocumentContent(
        String mediaType,
        String title,
        String normalizedText,
        Map<String, String> metadata,
        String structuredJson,
        @org.jspecify.annotations.Nullable UUID extractionArtifactId
) {
    public DocumentContent {
        java.util.Objects.requireNonNull(structuredJson, "structuredJson");
        metadata = Map.copyOf(metadata);
    }

    public DocumentContent(String mediaType, String title, String normalizedText, Map<String, String> metadata) {
        this(mediaType, title, normalizedText, metadata, "", null);
    }

    public DocumentContent withArtifact(java.util.UUID artifactId) {
        return new DocumentContent(mediaType, title, normalizedText, metadata,
                structuredJson, artifactId);
    }
}
