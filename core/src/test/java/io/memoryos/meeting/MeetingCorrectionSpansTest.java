package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Accepting a proposal changes the length of a line, so the marks left on it have to move with the words. Every case
 * here reads the mark back out of the rewritten text rather than trusting the numbers.
 */
class MeetingCorrectionSpansTest {
    @Test
    void marksAfterTheChangeMoveWithIt() {
        String before = "Nó ra tiếng Tát cô rồi.";
        String after = before.substring(0, 12) + "Tasco" + before.substring(18);
        var marks = List.of(new Meeting.Span(3, 5, 0.4), new Meeting.Span(12, 18, 0.3),
                new Meeting.Span(19, 22, 0.5));

        var moved = MeetingCorrectionService.shifted(marks, 12, 18, "Tasco".length());

        assertEquals(2, moved.size(), "the mark that covered the replaced words describes nothing now");
        assertEquals("ra", before.substring(moved.getFirst().start(), moved.getFirst().end()),
                "a mark before the change does not move");
        assertEquals("rồi", after.substring(moved.get(1).start(), moved.get(1).end()),
                "and one after it lands on the same word it did before");
    }

    @Test
    void aMarkOnPartOfAWordCoversTheWholeWord() {
        // Soniox scored "ở" of "Mở" and "Tr" of "Trực" on staging.
        var line = new Meeting.Utterance(java.util.UUID.randomUUID(), Meeting.Track.MIC, "1", 0, 1000,
                "Mở cửa, trực tiếp 51 này.", 0.5,
                List.of(new Meeting.Span(1, 2, 0.37), new Meeting.Span(8, 10, 0.3), new Meeting.Span(10, 12, 0.5),
                        new Meeting.Span(19, 20, 0.25)));

        var words = line.spans().stream().map(span -> line.text().substring(span.start(), span.end())).toList();

        assertEquals(List.of("Mở", "trực", "51"), words);
        assertEquals(0.3, line.spans().get(1).confidence(), 1e-9,
                "two pieces of one word become one mark, as unsure as its least sure piece");
    }

    @Test
    void aMarkOverlappingTheChangeIsDropped() {
        var marks = List.of(new Meeting.Span(4, 10, 0.5));

        assertTrue(MeetingCorrectionService.shifted(marks, 6, 12, 3).isEmpty());
    }

    @Test
    void aLongerReplacementMovesTheRestForward() {
        String before = "Khê rồi nhé.";
        var marks = List.of(new Meeting.Span(8, 11, 0.4));

        var moved = MeetingCorrectionService.shifted(marks, 0, 3, "Khét lẹt".length());

        String after = "Khét lẹt" + before.substring(3);
        assertEquals("nhé", after.substring(moved.getFirst().start(), moved.getFirst().end()));
    }
}
