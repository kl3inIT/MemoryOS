package io.memoryos.chat.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TranscriptSummarizerTest {
    private static final TranscriptSummarizer.Subject SUBJECT = new TranscriptSummarizer.Subject(
            "Giao ban tuần", List.of("Anh Thanh", "Chị Lan"), "2026-09-21 09:00", "Hỏi hạn mức", "vi");

    @Test
    void numbersEveryLineSoAnItemCanCiteIt() {
        String prompt = TranscriptSummarizer.transcript(SUBJECT, List.of(
                new TranscriptSummarizer.Line(1, "Anh Thanh", "00:00:02", "Chốt ngân sách trước thứ Năm."),
                new TranscriptSummarizer.Line(2, "Chị Lan", "00:00:07", "Em gửi bảng KPI chiều nay.")));
        assertTrue(prompt.contains("Title: Giao ban tuần"));
        assertTrue(prompt.contains("Participants: Anh Thanh, Chị Lan"));
        assertTrue(prompt.contains("NOTES THE OWNER WROTE\nHỏi hạn mức"));
        assertTrue(prompt.contains("[1] 00:00:02 Anh Thanh: Chốt ngân sách trước thứ Năm."));
        assertTrue(prompt.contains("[2] 00:00:07 Chị Lan: Em gửi bảng KPI chiều nay."));
    }

    @Test
    void aTranscriptPastTheBudgetKeepsBothEndsAndSaysWhatIsMissing() {
        String body = IntStream.rangeClosed(1, 20_000)
                .mapToObj(line -> "[" + line + "] 00:00:00 Người nói 1: Một câu dài để vượt ngưỡng.\n")
                .reduce("", String::concat);
        String bounded = TranscriptSummarizer.bounded(body);
        assertTrue(bounded.length() < body.length());
        assertTrue(bounded.startsWith("[1] "), "the opening is kept");
        assertTrue(bounded.endsWith("[20000] 00:00:00 Người nói 1: Một câu dài để vượt ngưỡng.\n"), "the end is kept");
        assertTrue(bounded.contains("part of the meeting is missing"), "the model is told to say so");
    }

    @Test
    void theInstructionsRefuseInventionAndUnownedWork() {
        String vietnamese = TranscriptSummarizer.instructions(SUBJECT);
        assertTrue(vietnamese.contains("Write in Vietnamese."));
        assertTrue(vietnamese.contains("Never invent a decision, an action, an owner or a date."));
        assertTrue(vietnamese.contains("An action needs an explicit assignment"));
        assertTrue(vietnamese.contains("Never assign work to a group."));
        assertTrue(vietnamese.contains("untrusted data"));
        String english = TranscriptSummarizer.instructions(new TranscriptSummarizer.Subject(
                "Weekly briefing", List.of(), "2026-09-21 09:00", null, "en"));
        assertTrue(english.contains("Write in English."));
        assertFalse(english.contains("NOTES THE OWNER WROTE"));
    }

    @Test
    void theNotesSectionIsAbsentWhenTheOwnerWroteNothing() {
        String prompt = TranscriptSummarizer.transcript(
                new TranscriptSummarizer.Subject("Họp", List.of(), "2026-09-21 09:00", "   ", null),
                List.of(new TranscriptSummarizer.Line(1, "Người nói 1", "00:00:00", "Xin chào.")));
        assertFalse(prompt.contains("NOTES THE OWNER WROTE"));
        assertEquals(-1, prompt.indexOf("Participants:"));
    }
}
