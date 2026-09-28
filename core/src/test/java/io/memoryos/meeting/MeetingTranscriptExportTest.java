package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/** What a person gets when they take the transcript away, read back out of the bytes that are produced. */
class MeetingTranscriptExportTest {
    private static final UUID MIC_1 = UUID.randomUUID();
    private static final UUID MIC_2 = UUID.randomUUID();

    private static Meeting.Detail meeting(String language) {
        return new Meeting.Detail(UUID.randomUUID(), "Giao ban tuần", Meeting.Kind.IN_PERSON, language,
                List.of("Anh Thanh", "Chị Lan"), List.of(), "", Meeting.Status.ENDED, "SONIOX", true,
                Instant.parse("2026-09-22T02:15:00Z"), Instant.parse("2026-09-22T03:00:00Z"), 1,
                List.of(new Meeting.Speaker(Meeting.Track.MIC, "1", null),
                        new Meeting.Speaker(Meeting.Track.MIC, "2", "Chị Lan")),
                List.of(new Meeting.Utterance(MIC_1, Meeting.Track.MIC, "1", 2_000, 6_400,
                                "Tuần này phải chốt ngân sách quý 4 trước thứ Năm.", 0.9),
                        new Meeting.Utterance(MIC_2, Meeting.Track.MIC, "2", 3_725_000, 3_729_000,
                                "Em gửi bảng KPI chiều nay.", 0.8)),
                new Meeting.Minutes(Meeting.MinutesStatus.NONE, null, "", "", null, List.of(), List.of()),
                new Meeting.Audio(Meeting.AudioStatus.NONE, null, null, 0, null), true, List.of(), List.of(),
                List.of());
    }

    @Test
    void theWordFileCarriesEveryLineWithItsTimeAndWhoeverSaidIt() throws Exception {
        byte[] bytes = MeetingTranscriptDocument.render(meeting("vi"));

        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = String.join("\n", document.getParagraphs().stream().map(p -> p.getText()).toList());
            assertTrue(text.contains("Giao ban tuần"));
            assertTrue(text.contains("Anh Thanh, Chị Lan"), "the people who were there are named once, at the top");
            assertTrue(text.contains("[00:02] Người nói 1: Tuần này phải chốt ngân sách quý 4 trước thứ Năm."));
            assertTrue(text.contains("[1:02:05] Chị Lan: Em gửi bảng KPI chiều nay."),
                    "a named speaker keeps their name, and an hour in the clock grows an hour");
        }
    }

    @Test
    void anEnglishMeetingNamesItsUnnamedSpeakersInEnglish() throws Exception {
        byte[] bytes = MeetingTranscriptDocument.render(meeting("en"));

        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = String.join("\n", document.getParagraphs().stream().map(p -> p.getText()).toList());
            assertTrue(text.contains("[00:02] Speaker 1:"));
        }
    }

    @Test
    void thePdfSaysTheSameThingAndDrawsVietnameseRatherThanQuestionMarks() throws Exception {
        byte[] bytes = MeetingTranscriptPdf.render(meeting("vi"));

        try (var document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Giao ban tuần"));
            assertTrue(text.contains("[00:02] Người nói 1:"));
            assertTrue(text.contains("ngân sách quý 4"), "the marks survive into the page");
            assertTrue(text.contains("[1:02:05] Chị Lan:"));
        }
    }

    @Test
    void theClockGrowsAnHourOnlyWhenTheMeetingDoes() {
        assertEquals("00:00", SpeakerNames.clock(0));
        assertEquals("00:02", SpeakerNames.clock(2_400));
        assertEquals("59:59", SpeakerNames.clock(3_599_999));
        assertEquals("1:00:00", SpeakerNames.clock(3_600_000));
    }

    @Test
    void everyVoiceIsNamedTheOneWayThePageNamesIt() {
        var speakers = List.of(new Meeting.Speaker(Meeting.Track.MIC, "1", null),
                new Meeting.Speaker(Meeting.Track.TAB, "2", " Chị Lan "));
        var online = SpeakerNames.of(speakers, Meeting.Kind.ONLINE, "vi");
        assertEquals("Chủ cuộc họp", online.of(Meeting.Track.MIC, "1"), "online, the microphone carries the owner");
        assertEquals("Chị Lan", online.of(Meeting.Track.TAB, "2"));
        assertEquals("Người nói 3", online.of(Meeting.Track.TAB, "3"));
        var inPerson = SpeakerNames.of(speakers, Meeting.Kind.IN_PERSON, "en");
        assertEquals("Speaker 1", inPerson.of(Meeting.Track.MIC, "1"), "in a room the microphone hears everyone");
        assertEquals("Speaker 2", inPerson.of(Meeting.Track.MIC, "2"), "a name belongs to one track's voice");
    }
}
