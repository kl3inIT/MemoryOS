package io.memoryos.retrieval;

import io.memoryos.connector.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A Source document the reader may open now. {@code generation} is the generation the live index serves, or null
 * while the document is not served there yet; {@code groups} are the names of the reader's Groups granted this
 * PRIVATE Source, sorted, and empty otherwise. {@code category} is DOCUMENT, SPREADSHEET, IMAGE, PRESENTATION or
 * OTHER.
 */
public record ShelfDocument(UUID documentId, @Nullable UUID generation, String filename, @Nullable String title,
        String mediaType, long sizeBytes, String category, Instant updatedAt, UUID sourceId, String sourceName,
        SourceType sourceType, @Nullable String providerUrl, Access access, List<String> groups) {
    public ShelfDocument {
        groups = List.copyOf(groups);
    }

    /** The predicate that admitted the reader: a PUBLIC Source, a Group grant, or the provider's own grants. */
    public enum Access { PUBLIC, GROUP, PROVIDER }
}
