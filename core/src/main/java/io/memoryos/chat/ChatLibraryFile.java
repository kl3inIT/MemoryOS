package io.memoryos.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One file in the owner's library, wherever Chat keeps it. {@code session} is absent for an upload, which
 * belongs to its owner rather than to one conversation; {@code usedBy} is what blocks deleting it.
 */
public record ChatLibraryFile(Source source, UUID id, String filename, String mediaType, long sizeBytes,
                              Instant createdAt, Category category, @Nullable UUID sessionId,
                              @Nullable String sessionTitle, List<Usage> usedBy) {
    public ChatLibraryFile { usedBy = List.copyOf(usedBy); }

    /** Where the file came from: an upload, a {@code run_python} result, or a generated image. */
    public enum Source { UPLOAD, GENERATED, IMAGE }

    public enum Category { DOCUMENT, SPREADSHEET, IMAGE, PRESENTATION, OTHER }

    public enum Sort { NEWEST, OLDEST, LARGEST, SMALLEST }

    public record Usage(Kind kind, UUID id, String name) {
        public enum Kind { AGENT, PROJECT }
    }

    public ChatLibraryFile withUsedBy(List<Usage> usages) {
        return new ChatLibraryFile(source, id, filename, mediaType, sizeBytes, createdAt, category,
                sessionId, sessionTitle, usages);
    }

    /** Only an upload can be attached to a Project or Agent, so only an upload is ever undeletable. */
    public boolean deletable() { return usedBy.isEmpty(); }
}
