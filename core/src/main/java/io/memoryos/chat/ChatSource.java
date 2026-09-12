package io.memoryos.chat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Evidence supplied to this answer. Historical evidence does not grant access to current source content. */
public record ChatSource(int citationId, @Nullable UUID documentId, @Nullable UUID generation, String title,
                         int startOrdinal, int endOrdinal, List<Provenance> provenance, @Nullable UUID fileId) {
    public ChatSource(int citationId, UUID documentId, UUID generation, String title,
                      int startOrdinal, int endOrdinal, List<Provenance> provenance) {
        this(citationId, documentId, generation, title, startOrdinal, endOrdinal, provenance, null);
    }
    public ChatSource {
        if (fileId == null) {
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
    public record Provenance(int ordinal, String provenanceJson) {
        public Provenance {
            if (ordinal < 0 || provenanceJson == null || provenanceJson.length() > 8192)
                throw new IllegalArgumentException("Invalid Chat source provenance");
        }
        @Override public @NonNull String toString() { return "Provenance[redacted]"; }
    }
    @Override public @NonNull String toString() { return "ChatSource[citationId=" + citationId + "]"; }
}
