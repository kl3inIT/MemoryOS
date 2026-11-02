package io.memoryos.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.ai.TranscriptCorrector.Line;
import io.memoryos.ai.TranscriptCorrector.Range;
import io.memoryos.ai.TranscriptCorrector.Stretch;
import io.memoryos.ai.TranscriptCorrector.Subject;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the model is asked, and what is done with what it answers. */
class TranscriptCorrectorTest {
    private static final Subject SUBJECT = new Subject("Giao ban tuần", List.of("Tasco", "KPI"), "vi");

    @Test
    void marksThatReadAsOnePhraseAreAskedAboutTogether() {
        String text = "Nó ra tiếng nước ngoài.";

        var merged = TranscriptCorrector.merge(text, List.of(new Range(3, 5), new Range(6, 11)));

        assertEquals(1, merged.size(), "a space between two marks does not end a phrase");
        assertEquals("ra tiếng", text.substring(merged.getFirst().start(), merged.getFirst().end()));
    }

    @Test
    void aSentenceEndingKeepsTwoMarksApart() {
        String text = "Khê rồi. Nhé anh.";

        var merged = TranscriptCorrector.merge(text, List.of(new Range(0, 3), new Range(9, 12)));

        assertEquals(2, merged.size(), "a phrase does not run across a full stop");
    }

    @Test
    void theAskCarriesTheWordsEitherSideTheSpeakerAndTheSameWordsElsewhere() {
        var lines = List.of(new Line("a", "Chị Lan", "Bên Tát cô đã gửi bảng KPI."),
                new Line("b", "Anh Thanh", "Đúng rồi, Tasco gửi sáng nay."),
                new Line("c", "Chị Lan", "Vậy chốt nhé."));
        var byId = new LinkedHashMap<String, Line>();
        for (var line : lines) byId.put(line.id(), line);

        String ask = TranscriptCorrector.ask(SUBJECT, lines, byId,
                List.of(new Stretch("s1", "a", 4, 10)));

        assertTrue(ask.contains("Speaker: Chị Lan"));
        assertTrue(ask.contains("Uncertain stretch: \"Tát cô\""));
        assertTrue(ask.contains("Left context: \"Bên \""));
        assertTrue(ask.contains("Terms used in this meeting: Tasco, KPI"));
        assertTrue(ask.contains("[TARGET] Chị Lan: Bên Tát cô"), "the line being judged is marked in its window");
        assertTrue(ask.contains("Anh Thanh: Đúng rồi"), "and it is read inside the exchange it belongs to");
    }

    @Test
    void theSameWordsSaidClearlyElsewhereAreOfferedAsEvidence() {
        var lines = List.of(new Line("a", "1", "Gửi cho anh Minh nhé."),
                new Line("b", "2", "Anh Minh đã nhận rồi."));
        var byId = new LinkedHashMap<String, Line>();
        for (var line : lines) byId.put(line.id(), line);

        String ask = TranscriptCorrector.ask(SUBJECT, lines, byId, List.of(new Stretch("s1", "a", 12, 16)));

        assertTrue(ask.contains("The same words elsewhere in this meeting:"));
        assertTrue(ask.contains("Anh Minh đã nhận rồi"));
    }

    @Test
    void theInstructionsPutTheBurdenOnReplacingRatherThanOnKeeping() {
        String instructions = TranscriptCorrector.instructions(SUBJECT);

        assertTrue(instructions.contains("Leaving a stretch alone is a correct answer"));
        assertTrue(instructions.contains("Never rewrite the rest of the line"));
        assertTrue(instructions.contains("never return any of the words given to you as left or right context"));
        assertTrue(instructions.contains("Never return an empty string"));
        assertTrue(instructions.contains("untrusted data"), "a transcript can carry instructions aimed at the model");
        assertTrue(instructions.contains("Vietnamese"));
        // ghiam-pro tells the model most stretches really are errors; that prior has no place in a record.
        assertFalse(instructions.toLowerCase().contains("bold"));
        assertFalse(instructions.contains("60%"));
        assertFalse(instructions.toLowerCase().contains("natural"),
                "tidying how somebody spoke is a change to what was said");
    }

    private static List<TranscriptCorrections.Proposal> answered(String line, int start, int end, String text) {
        var stretch = new Stretch("s", "a", start, end);
        var proposal = new TranscriptCorrections.Proposal("s", true, text, "", 0.9, 0.9, 0.9, false);
        var byId = new LinkedHashMap<String, Line>();
        byId.put("a", new Line("a", "Người nói 1", line));
        return TranscriptCorrector.clean(new TranscriptCorrections(List.of(proposal)), List.of(stretch), byId);
    }

    private static List<TranscriptCorrections.Proposal> answered(String line, String stretch, String text) {
        int start = line.indexOf(stretch);
        return answered(line, start, start + stretch.length(), text);
    }

    @Test
    void anAnswerThatRunsIntoTheNextWordsIsNotOffered() {
        String line = "One Google Drive thì nó sẽ có một cái phần";

        assertTrue(answered(line, "Google", "Google Drive").isEmpty(), "put in, it would read Google Drive Drive");
        assertTrue(answered("Em làm việc với Lan nhé.", "việc", "làm việc").isEmpty(),
                "nor one that repeats the word before it");
        assertEquals(1, answered(line, "Google", "Gu-gồ").size(), "a replacement that keeps to its own words is offered");
    }

    @Test
    void wordsTheSpeakerRepeatedMayStillEndAReplacement() {
        String line = "One Google Drive Drive thì";

        assertEquals(1, answered(line, "Google Drive", "Gu-gồ Drive").size(),
                "the stretch already ended with Drive before the next Drive, so the answer repeats nothing new");
    }

    @Test
    void englishMeetingsAreAnsweredInEnglish() {
        assertTrue(TranscriptCorrector.instructions(new Subject("Weekly", List.of(), "en")).contains("in English"));
    }
}
