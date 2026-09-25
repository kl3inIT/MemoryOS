package io.memoryos.document;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record DocumentContent(
        String mediaType,
        String title,
        String normalizedText,
        Map<String, String> metadata,
        String structuredJson,
        @Nullable UUID extractionArtifactId
) {
    public DocumentContent {
        Objects.requireNonNull(structuredJson, "structuredJson");
        metadata = Map.copyOf(metadata);
    }

    public DocumentContent(String mediaType, String title, String normalizedText, Map<String, String> metadata) {
        this(mediaType, title, normalizedText, metadata, "", null);
    }

    public DocumentContent withArtifact(UUID artifactId) {
        return new DocumentContent(mediaType, title, normalizedText, metadata,
                structuredJson, artifactId);
    }
}
