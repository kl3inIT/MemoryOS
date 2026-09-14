package io.memoryos.connector;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Dates belong to this source item; FILE dates describe upload, not extraction or reindex.
 * {@code providerFileId} identifies the provider object for presentation links only; it is not indexed
 * and grants no access: the provider enforces its own permissions when the link is opened.
 */
public record DocumentSourceMetadata(UUID sourceId, UUID itemId, SourceType type,
        @Nullable Instant createdAt, @Nullable Instant updatedAt, List<String> authors, @Nullable String providerFileId) {
    private static final Pattern DRIVE_FILE_ID = Pattern.compile("[A-Za-z0-9_-]{10,256}");

    public DocumentSourceMetadata { authors = List.copyOf(authors); }

    public DocumentSourceMetadata(UUID sourceId, UUID itemId, SourceType type,
            @Nullable Instant createdAt, @Nullable Instant updatedAt, List<String> authors) {
        this(sourceId, itemId, type, createdAt, updatedAt, authors, null);
    }

    /**
     * Drive's universal open URL, valid for native Docs/Sheets/Slides and stored binary files alike, so the
     * recorded media type (Slides are stored as exported PPTX) never selects an editor path.
     */
    public @Nullable String providerUrl() {
        return type == SourceType.GOOGLE_DRIVE && providerFileId != null && DRIVE_FILE_ID.matcher(providerFileId).matches()
                ? "https://drive.google.com/open?id=" + providerFileId : null;
    }

    public static @Nullable String providerUrl(List<DocumentSourceMetadata> origins) {
        return origins.stream().map(DocumentSourceMetadata::providerUrl).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
