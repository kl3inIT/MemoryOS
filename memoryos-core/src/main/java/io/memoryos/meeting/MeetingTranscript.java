package io.memoryos.meeting;

import java.util.ArrayList;
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
        var names = SpeakerNames.of(meeting);
        var lines = new ArrayList<Line>(meeting.utterances().size());
        for (var utterance : meeting.utterances())
            lines.add(new Line(SpeakerNames.clock(utterance.startMs()), names.of(utterance), utterance.text()));
        return List.copyOf(lines);
    }
}
