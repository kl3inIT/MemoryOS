package io.memoryos.meeting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The transcript as a reader reads it: every line with the time it was said and the name of whoever said it. Both
 * exports are built from this, so a Word file and a PDF of the same meeting say exactly the same thing.
 */
public final class MeetingTranscript {
    private MeetingTranscript() {}

    /** One line, already named and timed. */
    public record Line(String time, String speaker, String text) {}

    public static List<Line> lines(Meeting.Detail meeting) {
        var names = new HashMap<String, String>();
        for (var speaker : meeting.speakers())
            if (speaker.name() != null && !speaker.name().isBlank())
                names.put(speaker.track().name() + speaker.label(), speaker.name().strip());
        boolean english = "en".equals(meeting.language());
        var lines = new ArrayList<Line>(meeting.utterances().size());
        for (var utterance : meeting.utterances()) {
            String key = utterance.track().name() + utterance.speaker();
            String name = names.getOrDefault(key,
                    (english ? "Speaker " : "Người nói ") + utterance.speaker());
            lines.add(new Line(clock(utterance.startMs()), name, utterance.text()));
        }
        return List.copyOf(lines);
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
