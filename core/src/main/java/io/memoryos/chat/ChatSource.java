package io.memoryos.chat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Evidence supplied to this answer. Historical evidence does not grant access to current source content. */
public record ChatSource(int citationId, UUID documentId, UUID generation, String title,
                         int startOrdinal, int endOrdinal, List<Provenance> provenance) {
    public ChatSource {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(generation);
        if (citationId < 1 || citationId > 24 || title == null || title.length() > 1024
                || startOrdinal < 0 || endOrdinal < startOrdinal || endOrdinal > 9999)
            throw new IllegalArgumentException("Invalid Chat source");
        provenance = List.copyOf(provenance);
        if (provenance.isEmpty() || provenance.size() > 60 || provenance.stream().anyMatch(p -> p.ordinal() < startOrdinal || p.ordinal() > endOrdinal))
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
