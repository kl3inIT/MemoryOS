package io.memoryos.ingestion;

import java.util.Map;

public record ExtractionResult(
        String mediaType,
        String title,
        String normalizedText,
        Map<String, String> metadata
) {
    public ExtractionResult {
        metadata = Map.copyOf(metadata);
    }
}
