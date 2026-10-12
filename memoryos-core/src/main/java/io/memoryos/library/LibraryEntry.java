package io.memoryos.library;

import io.memoryos.connector.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of a library view other than the owned listing: an owned file, or a reference to something another
 * capability owns and lets the viewer read. {@code reason} is the projection of the rule that admitted the row;
 * {@code starred} and {@code openedAt} are the viewer's own marks. A meeting has no media type, size or category;
 * {@code meeting}, {@code agents} and {@code document} carry what only one kind has.
 */
public record LibraryEntry(Kind kind, UUID id, String name, @Nullable String mediaType, @Nullable Long sizeBytes,
                           LibraryFile.@Nullable Category category, Instant at, boolean owned, boolean starred,
                           @Nullable Instant openedAt, @Nullable String ownerName, Reason reason,
                           @Nullable UUID sessionId, @Nullable String sessionTitle, @Nullable UUID messageId,
                           @Nullable MeetingState meeting, List<Agent> agents, @Nullable DocumentLink document) {
    public LibraryEntry { agents = List.copyOf(agents); }

    /** What a row is. The first three are owned files; the others are reachable references. */
    public enum Kind {
        UPLOAD, GENERATED, IMAGE, MEETING, AGENT_FILE, DOCUMENT;

        /** Whether the viewer owns every row of this kind, so its star is the file's own favourite. */
        public boolean ownedFile() { return this == UPLOAD || this == GENERATED || this == IMAGE; }
    }

    /** Why the row is visible: nothing for an owner; the share, Groups, Agents or Source authority otherwise. */
    public record Reason(ReasonKind kind, List<String> names) {
        public Reason { names = List.copyOf(names); }
    }

    public enum ReasonKind { OWNER, MEMBER_SHARE, GROUP_SHARE, AGENT, PUBLIC_SOURCE, GROUP_SOURCE, PROVIDER_SOURCE }

    public record MeetingState(String status, boolean minutesReady, boolean hasTranscript, long durationMs) {}

    public record Agent(UUID id, String name) {}

    /** A Source document's served generation, absent while it is being indexed, and where it came from. */
    public record DocumentLink(@Nullable UUID generation, @Nullable String title, UUID sourceId, String sourceName,
                               SourceType sourceType, @Nullable String providerUrl) {}

    /** The same row with the viewer's marks. */
    LibraryEntry marked(boolean starred, @Nullable Instant openedAt) {
        return new LibraryEntry(kind, id, name, mediaType, sizeBytes, category, at, owned, starred, openedAt,
                ownerName, reason, sessionId, sessionTitle, messageId, meeting, agents, document);
    }

    LibraryEntry withOwnerName(@Nullable String owner) {
        return new LibraryEntry(kind, id, name, mediaType, sizeBytes, category, at, owned, starred, openedAt,
                owner, reason, sessionId, sessionTitle, messageId, meeting, agents, document);
    }
}
