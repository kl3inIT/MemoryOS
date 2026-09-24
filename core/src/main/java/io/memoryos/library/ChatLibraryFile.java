package io.memoryos.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One file in the owner's library, wherever Chat keeps it. {@code session} is absent for an upload, which
 * belongs to its owner rather than to one conversation; {@code usedBy} is what blocks deleting it. {@code messageId}
 * is the answer that produced an artifact, or, when the list is narrowed to one conversation, the first message
 * that attached an upload there; it is what "show in conversation" scrolls to.
 */
public record ChatLibraryFile(Source source, UUID id, String filename, String mediaType, long sizeBytes,
                              Instant createdAt, Category category, @Nullable UUID sessionId,
                              @Nullable String sessionTitle, @Nullable UUID messageId, boolean favorite,
                              UserFile.Status status, @Nullable String errorCode, @Nullable Instant deletedAt,
                              @Nullable Instant purgeAfter, List<Usage> usedBy) {
    public ChatLibraryFile { usedBy = List.copyOf(usedBy); }

    /** Where the file came from: an upload, a {@code run_python} result, a generated image, or a meeting. */
    public enum Source { UPLOAD, GENERATED, IMAGE, MEETING }

    public enum Category { DOCUMENT, SPREADSHEET, IMAGE, PRESENTATION, OTHER }

    public enum Sort { NEWEST, OLDEST, LARGEST, SMALLEST, NAME, DELETED }

    public record Usage(Kind kind, UUID id, String name) {
        public enum Kind { AGENT, PROJECT }
    }

    public ChatLibraryFile withUsedBy(List<Usage> usages) {
        return new ChatLibraryFile(source, id, filename, mediaType, sizeBytes, createdAt, category,
                sessionId, sessionTitle, messageId, favorite, status, errorCode, deletedAt, purgeAfter, usages);
    }

    /** Only an upload can be attached to a Project or Agent, so only an upload is ever undeletable. */
    public boolean deletable() { return usedBy.isEmpty(); }
}
