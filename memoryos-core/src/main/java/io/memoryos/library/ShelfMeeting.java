package io.memoryos.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A meeting as the library lists it for one reader. {@code ownerName} is the owner's display name, or their email
 * when they have none; {@code sharedWithActor} says the owner shared it with the reader by name, and {@code groups}
 * names the reader's own Groups it is shared with, sorted. A meeting the reader owns has neither.
 */
public record ShelfMeeting(UUID id, String title, boolean owned, String ownerName, boolean sharedWithActor,
                           List<String> groups, String status, boolean minutesReady, boolean hasTranscript,
                           long durationMs, Instant createdAt, @Nullable Instant endedAt) {
    public ShelfMeeting { groups = List.copyOf(groups); }
}
