package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The PDF biên bản, read back out of the bytes: what it says, what it is set in, and where its pages break. */
class MeetingMinutesPdfTest {
    private static final MeetingMinutesDocument.Heading HEADING = new MeetingMinutesDocument.Heading(
            "CÔNG TY CỔ PHẦN TASCO", "TẬP ĐOÀN TASCO", "12", "Giao ban tuần", "Phòng họp A, Hà Nội",
            "9 giờ ngày 21 tháng 9 năm 2026", "10 giờ 15", "Nguyễn Văn An", "Tổng giám đốc", "Trần Thị Lan",
            "Chuyên viên", List.of("Anh Thanh", "Chị Lan"));

    @Test
    void itSaysWhatTheWordFileSaysAndDrawsVietnameseRatherThanQuestionMarks() throws Exception {
        String text = text(MeetingMinutesPdf.render(meeting(List.of()), HEADING));

        for (String expected : List.of("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", "Độc lập - Tự do - Hạnh phúc",
                "CÔNG TY CỔ PHẦN TASCO", "BIÊN BẢN", "Về việc Giao ban tuần", "I. Thành phần tham dự:",
                "II. Nội dung cuộc họp:", "III. Kết luận cuộc họp:", "IV. Nhiệm vụ được giao:",
                "Chốt ngân sách quý 4 trước thứ Năm", "THƯ KÝ", "CHỦ TỌA", "Nguyễn Văn An", "Trần Thị Lan"))
            assertTrue(squash(text).contains(squash(expected)), () -> "missing: " + expected);
        assertFalse(text.contains("?"), "every Vietnamese letter has a glyph in the embedded face");
        assertTrue(text.lines().anyMatch(line -> squash(line).contains("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM")),
                "the quốc hiệu holds on one line, as the decree sets it");
    }

    @Test
    void theChosenFaceIsTheOneEmbeddedAndAnUnknownOneIsSetInTinos() throws Exception {
        assertEquals(List.of("Tinos-Bold", "Tinos-Regular"), faces(MeetingMinutesPdf.render(meeting(List.of()), HEADING)),
                "Times New Roman is licensed and cannot ship, so it is set in Tinos, drawn to the same metrics");
        assertEquals(List.of("Carlito-Bold", "Carlito-Regular"),
                faces(MeetingMinutesPdf.render(meeting(List.of()), withFace("Calibri"))));
        assertEquals(List.of("Arimo-Bold", "Arimo-Regular"),
                faces(MeetingMinutesPdf.render(meeting(List.of()), withFace("Arial"))));
        assertEquals(List.of("Tinos-Bold", "Tinos-Regular"),
                faces(MeetingMinutesPdf.render(meeting(List.of()), withFace("Comic Sans MS"))));
    }

    @Test
    void theSignaturesNeverOpenAPageOfTheirOwn() throws Exception {
        // Enough work to push the end of the document across a page, at every length that could strand the block.
        for (int count = 10; count <= 40; count++) {
            var actions = new ArrayList<Meeting.MinutesItem>();
            for (int i = 0; i < count; i++)
                actions.add(new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.ACTION,
                        "Rà soát lại số liệu báo cáo tài chính của đơn vị số " + i, "Anh Minh", "thứ Tư", null, null,
                        false));
            try (var document = Loader.loadPDF(MeetingMinutesPdf.render(meeting(actions), HEADING))) {
                var stripper = new PDFTextStripper();
                int last = document.getNumberOfPages();
                stripper.setStartPage(last);
                stripper.setEndPage(last);
                String lastPage = stripper.getText(document);
                int finalCount = count;
                assertTrue(lastPage.contains("CHỦ TỌA"), () -> finalCount + " actions: the signatures moved off the end");
                assertTrue(squash(lastPage).contains(squash("cùng ký vào biên bản")),
                        () -> finalCount + " actions: the signatures are alone on page " + last);
            }
        }
    }

    private static MeetingMinutesDocument.Heading withFace(String face) {
        return new MeetingMinutesDocument.Heading("Tasco", "", "", "Giao ban", "", "", "", "", "", "", "", List.of(),
                face);
    }

    private static String text(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Justified lines are drawn word by word, so the extracted text can carry extra spaces between them. */
    private static String squash(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static List<String> faces(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            var faces = new TreeSet<String>();
            for (var page : document.getPages())
                for (var name : page.getResources().getFontNames())
                    // An embedded subset is named with a six-letter tag, "ABCDEF+Tinos-Bold".
                    faces.add(page.getResources().getFont(name).getName().replaceFirst("^[A-Z]{6}\\+", ""));
            return List.copyOf(faces);
        }
    }

    private static Meeting.Detail meeting(List<Meeting.MinutesItem> actions) {
        var minutes = new Meeting.Minutes(Meeting.MinutesStatus.READY, null,
                "Cuộc họp chốt ngân sách quý 4 trước thứ Năm và giao bổ sung số liệu KPI.", "Giao ban tuần",
                Instant.parse("2026-09-21T03:15:00Z"),
                List.of(new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.DECISION,
                        "Chốt ngân sách quý 4 trước thứ Năm", null, null, null, null, false)),
                actions);
        return new Meeting.Detail(UUID.randomUUID(), "Giao ban tuần", Meeting.Kind.IN_PERSON, "vi",
                List.of("Anh Thanh", "Chị Lan"), List.of(), "", Meeting.Status.ENDED, "SONIOX", true,
                Instant.parse("2026-09-21T02:00:00Z"), Instant.parse("2026-09-21T03:15:00Z"), 3, List.of(), List.of(),
                minutes, new Meeting.Audio(Meeting.AudioStatus.NONE, null, null, 0, null), true, List.of());
    }
}
