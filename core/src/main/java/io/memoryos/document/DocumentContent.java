package io.memoryos.document;

import java.util.Map;
import java.util.UUID;

public record DocumentContent(
        String mediaType,
        String title,
        String normalizedText,
        Map<String, String> metadata,
        String structuredJson,
        String processingProfile,
        @org.jspecify.annotations.Nullable UUID extractionArtifactId
) {
    public DocumentContent {
        java.util.Objects.requireNonNull(structuredJson, "structuredJson");
        java.util.Objects.requireNonNull(processingProfile, "processingProfile");
        if (processingProfile.isBlank()
                || processingProfile.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
            throw new IllegalArgumentException("invalid processing profile");
        }
        metadata = Map.copyOf(metadata);
    }

    public DocumentContent(String mediaType, String title, String normalizedText, Map<String, String> metadata) {
        this(mediaType, title, normalizedText, metadata, "", "legacy-tika-v1", null);
    }

    public DocumentContent withArtifact(java.util.UUID artifactId, String profile) {
        return new DocumentContent(mediaType, title, normalizedText, metadata,
                structuredJson, profile, artifactId);
    }
}
