package io.memoryos.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
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
        // Joined rather than folded: reducing with String::concat copies the whole accumulator on every line, which
        // turns a transcript of this size into gigabytes of garbage and exhausts the one-gigabyte test heap.
        String body = IntStream.rangeClosed(1, 3_000)
                .mapToObj(line -> "[" + line + "] 00:00:00 Người nói 1: Một câu dài để vượt ngưỡng.\n")
                .collect(Collectors.joining());
        String bounded = TranscriptSummarizer.bounded(body);
        assertTrue(body.length() > 120_000, "the transcript has to pass the budget for this to mean anything");
        assertTrue(bounded.length() < body.length());
        assertTrue(bounded.startsWith("[1] "), "the opening is kept");
        assertTrue(bounded.endsWith("[3000] 00:00:00 Người nói 1: Một câu dài để vượt ngưỡng.\n"), "the end is kept");
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

    private static final List<TranscriptSummarizer.Line> MEETING = List.of(
            new TranscriptSummarizer.Line(1, "Người nói 1", "00:00", "Chào mọi người, bắt đầu nhé."),
            new TranscriptSummarizer.Line(2, "Người nói 2", "00:05", "Tuy nhiên để cấp Google account service ấy thì là anh Bình sẽ cấp."),
            new TranscriptSummarizer.Line(3, "Người nói 1", "00:12", "Còn thì bọn em sẽ gửi lại"),
            new TranscriptSummarizer.Line(4, "Người nói 1", "00:14", "đường tiếp cận."),
            new TranscriptSummarizer.Line(5, "Người nói 2", "00:20", "Ok, vậy chốt là dùng SDK."),
            new TranscriptSummarizer.Line(6, "Người nói 1", "00:25", "Vâng."),
            new TranscriptSummarizer.Line(7, "Người nói 2", "00:30", "Chốt dùng Postgres. Lan viết tài liệu cài đặt nhé."));

    private static TranscriptSummary minutes(List<TranscriptSummary.Decision> decisions,
                                             List<TranscriptSummary.Action> actions) {
        return TranscriptSummarizer.clean(new TranscriptSummary("Tóm tắt.", "Họp", decisions, actions, List.of()), MEETING);
    }

    private static TranscriptSummary.Action action(String owner) {
        return new TranscriptSummary.Action("Cấp Google service account.", owner, null, null, 2);
    }

    @Test
    void anOwnerIsOnePersonOrNobody() {
        assertEquals("anh Bình", minutes(List.of(), List.of(action("anh Bình"))).actions().getFirst().owner());
        assertEquals("Chủ cuộc họp", minutes(List.of(), List.of(action("Chủ cuộc họp"))).actions().getFirst().owner(),
                "the owner of an online meeting is one person");
        for (String notAPerson : List.of("Người nói 2", "speaker 1", "Nhóm phát triển", "các cháu", "Ban nhân sự",
                "Đơn vị quản lý dự án", "bọn em", "chị Mai với anh Khoa", "Minh và Khoa", "Lan, Quân")) {
            var checked = minutes(List.of(), List.of(action(notAPerson)));
            assertEquals(1, checked.actions().size(), "the work stays when its owner goes: " + notAPerson);
            assertNull(checked.actions().getFirst().owner(), notAPerson);
        }
    }

    @Test
    void aQuoteStaysOnItsLineMovesToWhereItWasSaidOrGoes() {
        var onItsLine = new TranscriptSummary.Action("Cấp service account.", "anh Bình", null,
                "Tuy nhiên để cấp Google account service ấy thì là anh Bình sẽ cấp.", 2);
        var oneOff = new TranscriptSummary.Decision("Dùng SDK.", "Ok, vậy chốt là dùng SDK", 6);
        var acrossLines = new TranscriptSummary.Action("Gửi lại phương án tiếp cận.", null, null,
                "Còn thì bọn em sẽ gửi lại đường tiếp cận.", 3);
        var nowhere = new TranscriptSummary.Action("Gửi Figma.", null, null, "Em gửi Figma cho anh nhé.", 4);
        var shortened = new TranscriptSummary.Action("Anh Bình cấp tài khoản.", "anh Bình", null,
                "để cấp Google account thì anh Bình sẽ cấp", 2);

        var checked = minutes(List.of(oneOff), List.of(onItsLine, acrossLines, nowhere, shortened));

        assertEquals(2, checked.actions().get(0).line());
        assertEquals(5, checked.decisions().getFirst().line(), "punctuation aside, the quote was said a line earlier");
        assertEquals("Ok, vậy chốt là dùng SDK", checked.decisions().getFirst().quote());
        assertEquals(3, checked.actions().get(1).line(), "a quote over two lines starts on the first");
        assertNull(checked.actions().get(2).quote(), "a quote nobody said is dropped");
        assertEquals(4, checked.actions().get(2).line(), "the item keeps its line");
        assertEquals("để cấp Google account service ấy thì là anh Bình sẽ cấp", checked.actions().get(3).quote(),
                "a quote the model shortened becomes the words said, from its first word to its last");
    }

    @Test
    void anItemWithoutALineTakesTheOnePlaceItsQuoteWasSaid() {
        var lineless = new TranscriptSummary.Action("Cấp service account.", null, null, "anh Bình sẽ cấp", 0);
        var pastTheEnd = new TranscriptSummary.Action("Trả lời.", null, null, "Vâng", 99);

        var checked = minutes(List.of(), List.of(lineless, pastTheEnd));

        assertEquals(2, checked.actions().get(0).line());
        assertEquals(6, checked.actions().get(1).line(), "a line past the transcript is no line at all");
        var ambiguous = minutes(List.of(), List.of(new TranscriptSummary.Action("Làm.", null, null, "sẽ", 0)))
                .actions().getFirst();
        assertEquals(0, ambiguous.line(), "a quote said in several places gives no line");
        assertNull(ambiguous.quote());
    }

    @Test
    void oneSentenceIsOneItem() {
        var decision = new TranscriptSummary.Decision("Tích hợp qua SDK.", "Ok, vậy chốt là dùng SDK.", 5);
        var action = new TranscriptSummary.Action("Xuất SDK để AI gọi sang hệ thống.", null, null,
                "Ok, vậy chốt là dùng SDK.", 5);
        var sameAgain = new TranscriptSummary.Action("Xuất SDK để AI gọi sang hệ thống", null, null, null, 0);
        var otherWork = new TranscriptSummary.Action("Viết tài liệu hướng dẫn dùng SDK.", null, null,
                "Ok, vậy chốt là dùng SDK.", 5);

        var checked = minutes(List.of(decision), List.of(action, sameAgain, otherWork));

        assertTrue(checked.decisions().isEmpty(), "the action on the same sentence carries the decision");
        assertEquals(List.of("Xuất SDK để AI gọi sang hệ thống.", "Viết tài liệu hướng dẫn dùng SDK."),
                checked.actions().stream().map(TranscriptSummary.Action::text).toList(),
                "the same words are one action wherever they point; different work on one line stays");
    }

    @Test
    void aDecisionBesideAnActionInTheSameLineStays() {
        var decision = new TranscriptSummary.Decision("Dùng Postgres.", "Chốt dùng Postgres.", 7);
        var action = new TranscriptSummary.Action("Viết tài liệu cài đặt.", "Lan", null,
                "Lan viết tài liệu cài đặt nhé.", 7);

        var checked = minutes(List.of(decision), List.of(action));

        assertEquals(1, checked.decisions().size(), "two sentences in one line are two items");
        assertEquals(1, checked.actions().size());
    }

    @Test
    void aRepeatAddsWhatTheFirstOneLackedAndWorkOfTwoPeopleStaysTwo() {
        var bare = new TranscriptSummary.Action("Xuất SDK để AI gọi sang hệ thống.", null, null, null, 0);
        var full = new TranscriptSummary.Action("Xuất SDK để AI gọi sang hệ thống", "anh Bình", "thứ Sáu",
                "Ok, vậy chốt là dùng SDK.", 5);

        var merged = minutes(List.of(), List.of(bare, full)).actions();

        assertEquals(1, merged.size());
        assertEquals("Xuất SDK để AI gọi sang hệ thống.", merged.getFirst().text());
        assertEquals("anh Bình", merged.getFirst().owner());
        assertEquals("thứ Sáu", merged.getFirst().due());
        assertEquals(5, merged.getFirst().line());
        assertEquals("Ok, vậy chốt là dùng SDK.", merged.getFirst().quote());

        var other = new TranscriptSummary.Action("Xuất SDK để AI gọi sang hệ thống", "chị Mai", null, null, 5);
        assertEquals(2, minutes(List.of(), List.of(full, other)).actions().size());
    }

    @Test
    void aQuoteMatchesWholeWordsOnly() {
        var line = List.of("Dùng SDKs.");

        assertNull(MinutesChecks.cite("SDK", 1, line, line.stream().map(MinutesChecks::bare).toList()).quote());
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
