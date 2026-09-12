package io.memoryos.chat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Evidence supplied to this answer. Historical evidence does not grant access to current source content. */
public record ChatSource(int citationId, @Nullable UUID documentId, @Nullable UUID generation, String title,
                         int startOrdinal, int endOrdinal, List<Provenance> provenance, @Nullable UUID fileId,
                         @Nullable FileLocation fileLocation) {
    public ChatSource(int citationId, @Nullable UUID documentId, @Nullable UUID generation, String title,
                      int startOrdinal, int endOrdinal, List<Provenance> provenance, @Nullable UUID fileId) {
        this(citationId, documentId, generation, title, startOrdinal, endOrdinal, provenance, fileId, null);
    }
    public ChatSource(int citationId, UUID documentId, UUID generation, String title,
                      int startOrdinal, int endOrdinal, List<Provenance> provenance) {
        this(citationId, documentId, generation, title, startOrdinal, endOrdinal, provenance, null);
    }
    public ChatSource {
        if (fileId == null) {
            if (fileLocation != null) throw new IllegalArgumentException("Only file citations have a file location");
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(generation);
        } else if (documentId != null || generation != null || startOrdinal != 0 || endOrdinal != 0 || !provenance.isEmpty()) {
            throw new IllegalArgumentException("File citations identify a file, not a fabricated document passage");
        }
        if (citationId < 1 || citationId > 24 || title == null || title.length() > 1024
                || startOrdinal < 0 || endOrdinal < startOrdinal || endOrdinal > 9999)
            throw new IllegalArgumentException("Invalid Chat source");
        provenance = List.copyOf(provenance);
        if (fileId == null && provenance.isEmpty() || provenance.size() > 60 || provenance.stream().anyMatch(p -> p.ordinal() < startOrdinal || p.ordinal() > endOrdinal))
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
