package io.memoryos.meeting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Meeting read models. Every value belongs to the owner's own meeting. */
public final class Meeting {
    private Meeting() {}

    /** Online: the owner's microphone and the shared meeting tab. In person: one microphone hears the room. */
    public enum Kind { ONLINE, IN_PERSON }

    public enum Status { RECORDING, ENDED }

    /** The audio source of an utterance. */
    public enum Track { MIC, TAB }

    /** Where a meeting's generated minutes are: never asked for, queued, running, written or given up on. */
    public enum MinutesStatus { NONE, PENDING, RUNNING, READY, FAILED }

    public enum ItemKind { DECISION, ACTION }

    /** A decision the meeting reached or work it handed out, with the line it rests on. */
    public record MinutesItem(UUID id, ItemKind kind, String text, @Nullable String owner, @Nullable String due,
                              @Nullable String quote, @Nullable UUID sourceUtteranceId, boolean done) {}

    /** What a run of the minutes job produced, as the owner reads it. */
    public record Minutes(MinutesStatus status, @Nullable String failure, String summary, String kind,
                          @Nullable Instant generatedAt, List<MinutesItem> decisions, List<MinutesItem> actions) {}

    public record Summary(UUID id, String title, Kind kind, Status status, int participants, long durationMs,
                          Instant createdAt, @Nullable Instant endedAt) {}

    public record Detail(UUID id, String title, Kind kind, @Nullable String language, List<String> participants,
                         List<String> terms, String notes, Status status, @Nullable String provider, boolean diarized,
                         Instant createdAt, @Nullable Instant endedAt, long revision, List<Speaker> speakers,
                         List<Utterance> utterances, Minutes minutes) {}

    public record Speaker(Track track, String label, @Nullable String name) {}

    public record Utterance(UUID id, Track track, String speaker, long startMs, long endMs, String text, double confidence) {}

    /** What the owner enters before recording. */
    public record Draft(String title, Kind kind, @Nullable String language, List<String> participants, List<String> terms) {}
}
