package io.memoryos.chat;

import io.memoryos.connector.SourceType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Evidence supplied to this answer. Historical evidence does not grant access to current source content.
 * {@code mediaType} and {@code sourceTypes} describe the evidence when it was cited, for presentation only;
 * answers saved before they existed carry null and an empty list.
 */
public record ChatSource(int citationId, @Nullable UUID documentId, @Nullable UUID generation, String title,
                         int startOrdinal, int endOrdinal, List<Provenance> provenance, @Nullable UUID fileId,
                         @Nullable FileLocation fileLocation, @Nullable WebLocation web,
                         @Nullable String mediaType, List<SourceType> sourceTypes, @Nullable String providerUrl) {
    private static final String DRIVE_OPEN = "https://drive.google.com/open?id=";

    public ChatSource(int citationId, @Nullable UUID documentId, @Nullable UUID generation, String title,
                      int startOrdinal, int endOrdinal, List<Provenance> provenance, @Nullable UUID fileId,
                      @Nullable FileLocation fileLocation, @Nullable WebLocation web) {
        this(citationId, documentId, generation, title, startOrdinal, endOrdinal, provenance, fileId, fileLocation, web, null, List.of(), null);
    }
    public ChatSource(int citationId, @Nullable UUID documentId, @Nullable UUID generation, String title,
                      int startOrdinal, int endOrdinal, List<Provenance> provenance, @Nullable UUID fileId, @Nullable FileLocation fileLocation) {
        this(citationId, documentId, generation, title, startOrdinal, endOrdinal, provenance, fileId, fileLocation, null);
    }
    public record WebLocation(String url, String excerpt, java.time.Instant retrievedAt) {
        public WebLocation {
            io.memoryos.chat.web.WebHttp.pageUri(url);
            if (excerpt == null || excerpt.length() > 4000 || retrievedAt == null) throw new IllegalArgumentException("Invalid Web evidence");
        }
    }
    public ChatSource(int citationId, @Nullable UUID documentId, @Nullable UUID generation, String title,
                      int startOrdinal, int endOrdinal, List<Provenance> provenance, @Nullable UUID fileId) {
        this(citationId, documentId, generation, title, startOrdinal, endOrdinal, provenance, fileId, null);
    }
    public ChatSource(int citationId, UUID documentId, UUID generation, String title,
                      int startOrdinal, int endOrdinal, List<Provenance> provenance) {
        this(citationId, documentId, generation, title, startOrdinal, endOrdinal, provenance, null);
    }
    public ChatSource {
        sourceTypes = sourceTypes == null ? List.of() : sourceTypes.stream().distinct().toList();
        if (mediaType != null && (mediaType.isBlank() || mediaType.length() > 160) || sourceTypes.size() > SourceType.values().length
                || providerUrl != null && (!providerUrl.startsWith(DRIVE_OPEN) || providerUrl.length() > DRIVE_OPEN.length() + 256
                        || !providerUrl.substring(DRIVE_OPEN.length()).matches("[A-Za-z0-9_-]{10,256}")))
            throw new IllegalArgumentException("Invalid Chat source presentation metadata");
        if (web != null) {
            if (fileId != null || fileLocation != null || documentId != null || generation != null || startOrdinal != 0 || endOrdinal != 0 || !provenance.isEmpty()
                    || mediaType != null || !sourceTypes.isEmpty() || providerUrl != null)
                throw new IllegalArgumentException("Web citations identify a URL, not a document or file");
        } else if (fileId == null) {
            if (fileLocation != null) throw new IllegalArgumentException("Only file citations have a file location");
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(generation);
        } else if (documentId != null || generation != null || startOrdinal != 0 || endOrdinal != 0 || !provenance.isEmpty()) {
            throw new IllegalArgumentException("File citations identify a file, not a fabricated document passage");
        }
        if (citationId < 1 || title == null || title.length() > 1024
                || startOrdinal < 0 || endOrdinal < startOrdinal || endOrdinal > 9999)
            throw new IllegalArgumentException("Invalid Chat source");
        provenance = List.copyOf(provenance);
        if (web == null && fileId == null && provenance.isEmpty() || provenance.size() > 60 || provenance.stream().anyMatch(p -> p.ordinal() < startOrdinal || p.ordinal() > endOrdinal))
            throw new IllegalArgumentException("Invalid Chat source provenance");
    }
    public record FileLocation(@Nullable Integer offset, @Nullable Integer count,
                               @Nullable UUID generation, @Nullable Integer ordinal) {
        public FileLocation {
            boolean text = offset != null && count != null && generation == null && ordinal == null
                    && offset >= 0 && offset <= 2000000 && count > 0 && count <= 16000;
            boolean passage = offset == null && count == null && generation != null && ordinal != null
                    && ordinal >= 0 && ordinal <= 9999;
            if (!text && !passage) throw new IllegalArgumentException("Invalid file citation location");
        }
    }
    public record Provenance(int ordinal, String provenanceJson) {
        public Provenance {
            if (ordinal < 0 || provenanceJson == null || provenanceJson.length() > 8192)
                throw new IllegalArgumentException("Invalid Chat source provenance");
        }
        @Override public @NonNull String toString() { return "Provenance[redacted]"; }
    }
    @Override public @NonNull String toString() { return "ChatSource[citationId=" + citationId + "]"; }
}
