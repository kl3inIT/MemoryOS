package io.memoryos.document;

import java.util.List;
import java.util.Objects;

/** One current, bounded passage with locations in the extraction artifact. */
public record DocumentChunk(int ordinal, String content, List<String> headings,
        int blockIndex, int part, String provenanceJson, String contentSha256, int tokenCount) {
    public static final String CONVENTION = "structured-cl100k-768-v1";
    public DocumentChunk {
        if (ordinal < 0 || blockIndex < 0 || part < 0 || tokenCount < 1 || tokenCount > 768) {
            throw new IllegalArgumentException("invalid chunk bounds");
        }
        if (Objects.requireNonNull(content).isBlank()) throw new IllegalArgumentException("empty chunk");
        headings = List.copyOf(headings);
        Objects.requireNonNull(provenanceJson);
        Objects.requireNonNull(contentSha256);
    }
}
