package io.memoryos.document;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One current, bounded passage with locations in the extraction artifact.
 *
 * @param linesJson the text lines the OCR located in the blocks this passage cites, as a JSON array of
 *        {@code {page_no, text, bbox}} in the space of those blocks' boxes; null when none was located. Kept
 *        beside the provenance rather than in it, so a citation stays within Chat's provenance bound.
 */
public record DocumentChunk(int ordinal, String content, List<String> headings,
        int blockIndex, int part, String provenanceJson, String contentSha256, int tokenCount,
        @Nullable String linesJson) {
    public static final String CONVENTION = "structured-cl100k-768-v4";
    /** The most characters a passage's lines take: some 300 lines, far more than 768 tokens hold. */
    public static final int MAX_LINES_JSON = 65_536;

    /** A passage without located lines. */
    public DocumentChunk(int ordinal, String content, List<String> headings, int blockIndex, int part,
            String provenanceJson, String contentSha256, int tokenCount) {
        this(ordinal, content, headings, blockIndex, part, provenanceJson, contentSha256, tokenCount, null);
    }

    public DocumentChunk {
        if (ordinal < 0 || blockIndex < 0 || part < 0 || tokenCount < 1 || tokenCount > 768) {
            throw new IllegalArgumentException("invalid chunk bounds");
        }
        if (Objects.requireNonNull(content).isBlank()) throw new IllegalArgumentException("empty chunk");
        headings = List.copyOf(headings);
        Objects.requireNonNull(provenanceJson);
        Objects.requireNonNull(contentSha256);
        if (linesJson != null && (linesJson.isBlank() || linesJson.length() > MAX_LINES_JSON)) {
            throw new IllegalArgumentException("invalid chunk lines");
        }
    }
}
