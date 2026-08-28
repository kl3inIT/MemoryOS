package io.memoryos.document;

import java.util.Map;

public record DocumentContent(
        String mediaType,
        String title,
        String normalizedText,
        Map<String, String> metadata
) {
    public DocumentContent {
        metadata = Map.copyOf(metadata);
    }
}
