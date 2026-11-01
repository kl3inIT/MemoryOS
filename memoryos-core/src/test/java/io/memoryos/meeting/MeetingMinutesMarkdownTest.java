package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What of a meeting reaches a conversation: the minutes, and never the words they were drawn from. */
class MeetingMinutesMarkdownTest {
    @Test
    void carriesTheSummaryTheDecisionsAndTheWorkWithItsOwners() {
        String text = render(meeting());
        assertTrue(text.startsWith("# Giao ban tuần · Khối Tài chính"));
        assertTrue(text.contains("Loại: Giao ban tuần"));
        assertTrue(text.contains("Thành phần: Anh Thanh, Chị Lan"));
        assertTrue(text.contains("## Tóm tắt\n\nCuộc họp chốt ngân sách quý 4 trước thứ Năm."));
        assertTrue(text.contains("## Quyết định"));
        assertTrue(text.contains("- Chốt ngân sách quý 4 trước thứ Năm"));
        assertTrue(text.contains("## Việc cần làm"));
        assertTrue(text.contains("- Kiểm tra bảng cân đối — Anh Minh (hạn: thứ Tư)"));
        assertTrue(text.contains("  > Anh Minh kiểm tra lại các bảng cân đối."),
                "the sentence an item rests on travels with it");
    }

    @Test
    void neverCarriesTheTranscript() {
        String text = render(meeting());
        assertFalse(text.contains("Chuyện lương thưởng để bàn riêng"),
                "raw speech stays on the meeting page: a transcript read back against its minutes is what "
                        + "ATG Capital v. Lane turned on");
        assertFalse(text.contains("Transcript\n"), "and no section invites one");
        assertTrue(text.contains("Transcript đầy đủ ở trang cuộc họp."), "the file says where the words are");
    }

    @Test
    void theFileIsNamedAfterTheMeetingAndIsSafeOnDisk() {
        assertEquals("Giao ban tuần · Khối Tài chính.md", MeetingMinutesMarkdown.filename(meeting()));
        var awkward = new Meeting.Detail(UUID.randomUUID(), "Họp 12/9: KPI \"quý 4\"", Meeting.Kind.IN_PERSON, "vi",
                List.of(), List.of(), "", Meeting.Status.ENDED, null, false, Instant.parse("2026-09-21T02:00:00Z"),
                null, 1, List.of(), List.of(), minutes(), audio(), true, List.of());
        String name = MeetingMinutesMarkdown.filename(awkward);
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\""));
        assertTrue(name.endsWith(".md"));
    }

    @Test
    void aMeetingWithNothingDecidedStillRendersItsHeading() {
        var bare = new Meeting.Detail(UUID.randomUUID(), "Họp nhanh", Meeting.Kind.IN_PERSON, "vi", List.of(),
                List.of(), "", Meeting.Status.ENDED, null, false, Instant.parse("2026-09-21T02:00:00Z"), null, 1,
                List.of(), List.of(),
                new Meeting.Minutes(Meeting.MinutesStatus.READY, null, "", "", null, List.of(), List.of()),
                audio(), true, List.of());
        String text = render(bare);
        assertTrue(text.startsWith("# Họp nhanh"));
        assertFalse(text.contains("## Quyết định"));
        assertFalse(text.contains("## Việc cần làm"));
    }

    private static String render(Meeting.Detail meeting) {
        return new String(MeetingMinutesMarkdown.render(meeting), StandardCharsets.UTF_8);
    }

    private static Meeting.Audio audio() {
        return new Meeting.Audio(Meeting.AudioStatus.NONE, null, null, 0, null);
    }

    private static Meeting.Minutes minutes() {
        return new Meeting.Minutes(Meeting.MinutesStatus.READY, null,
                "Cuộc họp chốt ngân sách quý 4 trước thứ Năm.", "Giao ban tuần",
                Instant.parse("2026-09-21T03:15:00Z"),
                List.of(new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.DECISION,
                        "Chốt ngân sách quý 4 trước thứ Năm", null, null, null, null, false)),
                List.of(new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.ACTION,
                        "Kiểm tra bảng cân đối", "Anh Minh", "thứ Tư", "Anh Minh kiểm tra lại các bảng cân đối.",
                        null, false)));
    }

    private static Meeting.Detail meeting() {
        var said = new Meeting.Utterance(UUID.randomUUID(), Meeting.Track.MIC, "1", 0, 4000,
                "Chuyện lương thưởng để bàn riêng, đừng ghi vào.", 0.9);
        return new Meeting.Detail(UUID.randomUUID(), "Giao ban tuần · Khối Tài chính", Meeting.Kind.IN_PERSON, "vi",
                List.of("Anh Thanh", "Chị Lan"), List.of(), "Ghi chú riêng của chủ toạ", Meeting.Status.ENDED,
                "SONIOX", true, Instant.parse("2026-09-21T02:00:00Z"), Instant.parse("2026-09-21T03:15:00Z"), 4,
                List.of(), List.of(said), minutes(), audio(), true, List.of());
    }
}
