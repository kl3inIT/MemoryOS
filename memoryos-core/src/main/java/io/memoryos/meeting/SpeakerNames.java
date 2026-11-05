package io.memoryos.meeting;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Who said a line, as everything MemoryOS writes from a transcript names them: the exports, the minutes the model
 * writes and the corrections it proposes. A voice the owner named is that name; online, the unnamed microphone is the
 * owner, because it carries nobody else; any other voice is numbered by its label. The page follows the same rule,
 * except that it calls the owner "Bạn" to the owner reading it; a file and a prompt are read by others.
 */
final class SpeakerNames {
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private final Map<String, String> named = new HashMap<>();
    private final List<Meeting.Speaker> speakers;
    private final boolean ownerOnMic;
    private final boolean english;

    private SpeakerNames(List<Meeting.Speaker> speakers, Meeting.Kind kind, @Nullable String language) {
        for (var speaker : speakers)
            if (speaker.name() != null && !speaker.name().isBlank())
                named.put(key(speaker.track(), speaker.label()), speaker.name().strip());
        this.speakers = List.copyOf(speakers);
        this.ownerOnMic = kind == Meeting.Kind.ONLINE;
        this.english = "en".equals(language);
    }

    static SpeakerNames of(List<Meeting.Speaker> speakers, Meeting.Kind kind, @Nullable String language) {
        return new SpeakerNames(speakers, kind, language);
    }

    static SpeakerNames of(Meeting.Detail meeting) {
        return of(meeting.speakers(), meeting.kind(), meeting.language());
    }

    /** The one key a voice is known by in a meeting: its track and its label. */
    static String key(Meeting.Track track, String label) {
        return track.name() + '\u0000' + label;
    }

    String of(Meeting.Utterance utterance) {
        return of(utterance.track(), utterance.speaker());
    }

    String of(Meeting.Track track, String label) {
        String name = named.get(key(track, label));
        if (name != null) return name;
        if (ownerOnMic && track == Meeting.Track.MIC) return english ? "Owner" : "Chủ cuộc họp";
        return (english ? "Speaker " : "Người nói ") + label;
    }

    /** Who owns a piece of work: the voice it points at, by its name now, or the name written on it. */
    @Nullable String owner(Meeting.MinutesItem item) {
        var speaker = item.ownerSpeaker();
        return speaker == null ? item.owner() : of(speaker.track(), speaker.label());
    }

    /**
     * The voice shown under this name, compared without case or spacing, when exactly one voice is; a name two voices
     * share points at neither.
     */
    Meeting.@Nullable SpeakerRef voice(String shown) {
        String wanted = fold(shown);
        Meeting.SpeakerRef found = null;
        for (var speaker : speakers) {
            if (!fold(of(speaker.track(), speaker.label())).equals(wanted)) continue;
            if (found != null) return null;
            found = new Meeting.SpeakerRef(speaker.track(), speaker.label());
        }
        return found;
    }

    /** Whether two names are the same name, as {@link #voice} compares them: without case or spacing. */
    static boolean sameName(String left, String right) {
        return fold(left).equals(fold(right));
    }

    private static String fold(String text) {
        return SPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).replaceAll(" ")
                .strip();
    }

    /** {@code mm:ss} within the hour, {@code h:mm:ss} beyond it, as the meeting page shows the same times. */
    static String clock(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        long hours = seconds / 3600;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, (seconds % 3600) / 60, seconds % 60)
                : "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
