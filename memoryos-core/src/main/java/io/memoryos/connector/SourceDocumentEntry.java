package io.memoryos.connector;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Document the reader may open now, listed once under its first readable mapping (by Source, then item).
 * {@code generation} is the served generation only when it was written to {@code indexIdentity}; {@code groups} are
 * the names of the reader's Groups granted a PRIVATE Source, sorted, and empty for other Sources.
 */
public record SourceDocumentEntry(UUID documentId, @Nullable UUID generation, String filename, @Nullable String title,
        String mediaType, long sizeBytes, String category, Instant updatedAt, UUID sourceId, String sourceName,
        SourceType sourceType, @Nullable String providerUrl, SourceAccess access, List<String> groups) {
    public SourceDocumentEntry {
        groups = List.copyOf(groups);
    }
}
