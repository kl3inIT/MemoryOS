package io.memoryos.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

class MeetingMinutesDocumentTest {
    private static final MeetingMinutesDocument.Heading HEADING = new MeetingMinutesDocument.Heading(
            "CÔNG TY CỔ PHẦN TASCO", "TẬP ĐOÀN TASCO", "12", "giao ban tuần Khối Tài chính", "Phòng họp A, Hà Nội",
            "09 giờ 00 ngày 21 tháng 9 năm 2026", "10 giờ 15 cùng ngày", "Nguyễn Văn An", "Giám đốc Tài chính",
            "Trần Thị Bình", "Chuyên viên", List.of("Anh Thanh", "Chị Lan"));

    @Test
    void followsTheDecreeSLayoutAndCarriesTheMinutes() throws Exception {
        var lines = read(MeetingMinutesDocument.render(meeting(
                new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.DECISION, "Chốt ngân sách quý 4", null, null,
                        "Chốt ngân sách quý 4.", null, false),
                new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.ACTION, "Kiểm tra bảng cân đối", "Anh Minh",
                        "thứ Tư", "Anh Minh kiểm tra bảng cân đối.", null, false)), HEADING));

        assertTrue(lines.contains("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM"));
        assertTrue(lines.contains("Độc lập - Tự do - Hạnh phúc"));
        assertTrue(lines.contains("CÔNG TY CỔ PHẦN TASCO"));
        assertTrue(lines.contains("Số: 12/BB"));
        assertTrue(lines.contains("BIÊN BẢN"));
        assertTrue(lines.contains("Về việc giao ban tuần Khối Tài chính"));
        assertTrue(lines.contains("Thời gian bắt đầu: 09 giờ 00 ngày 21 tháng 9 năm 2026"));
        assertTrue(lines.contains("Địa điểm: Phòng họp A, Hà Nội"));
        // The subject is under the title; the opening does not say it a second time.
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("Diễn ra cuộc họp")));

        assertTrue(lines.contains("I. Thành phần tham dự:"));
        assertTrue(lines.contains("1. Chủ trì: Ông/Bà Nguyễn Văn An - Chức vụ: Giám đốc Tài chính"));
        assertTrue(lines.contains("2. Thư ký: Ông/Bà Trần Thị Bình - Chức vụ: Chuyên viên"));
        assertTrue(lines.contains("- Anh Thanh"));
        assertTrue(lines.contains("- Chị Lan"));

        assertTrue(lines.contains("II. Nội dung cuộc họp:"));
        assertTrue(lines.contains("Cuộc họp chốt ngân sách quý 4 trước thứ Năm."));
        assertTrue(lines.contains("III. Kết luận cuộc họp:"));
        assertTrue(lines.contains("1. Chốt ngân sách quý 4"));
        assertTrue(lines.contains("IV. Nhiệm vụ được giao:"));
        assertTrue(lines.contains("1. Kiểm tra bảng cân đối do Anh Minh thực hiện, thời hạn thứ Tư."),
                "an action reads as an assignment with its owner and its deadline");
        assertTrue(lines.contains("Cuộc họp kết thúc vào lúc 10 giờ 15 cùng ngày, nội dung cuộc họp đã được các thành"
                + " viên dự họp thông qua và cùng ký vào biên bản./."));
        assertTrue(lines.contains("THƯ KÝ"));
        assertTrue(lines.contains("CHỦ TỌA"));
        assertTrue(lines.contains("Nguyễn Văn An"), "the chair is typed under their signature line");
    }

    @Test
    void anActionWithoutAnOwnerOrADeadlineStaysASentence() throws Exception {
        var lines = read(MeetingMinutesDocument.render(meeting(
                new Meeting.MinutesItem(UUID.randomUUID(), Meeting.ItemKind.ACTION, "Gửi lại bảng KPI.", null, null,
                        null, null, false)), HEADING));
        assertTrue(lines.contains("1. Gửi lại bảng KPI."));
        assertFalse(lines.stream().anyMatch(line -> line.contains("thực hiện")));
    }

    @Test
    void anEmptyHeadingFieldPrintsAsAnEllipsisToWriteOn() throws Exception {
        var empty = new MeetingMinutesDocument.Heading("", "", "", "", "", "", "", "", "", "", "", List.of());
        var lines = read(MeetingMinutesDocument.render(meeting(), empty));
        assertTrue(lines.contains("Số: …/BB"));
        assertTrue(lines.contains("Về việc …"));
        assertTrue(lines.contains("1. Chủ trì: Ông/Bà …"), "a chair with no role keeps the name field alone");
        assertTrue(lines.contains("- …"));
        assertTrue(lines.contains("…"), "a section with no items still shows its placeholder");
    }

    @Test
    void theDecreeSFontAndSizesAreUsedThroughout() throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(
                MeetingMinutesDocument.render(meeting(), HEADING)))) {
            var runs = new ArrayList<XWPFRun>();
            document.getParagraphs().forEach(paragraph -> runs.addAll(paragraph.getRuns()));
            assertFalse(runs.isEmpty());
            runs.forEach(run -> {
                assertEquals("Times New Roman", run.getFontFamily());
                assertTrue(run.getFontSizeAsDouble() >= 11 && run.getFontSizeAsDouble() <= 14,
                        "Nghị định 30 asks for 13 to 14 points, and a caption may be smaller");
            });
            assertTrue(document.getTables().stream().noneMatch(MeetingMinutesDocumentTest::bordered),
                    "the letterhead and the signature block are laid out, not drawn");
        }
    }

    @Test
    void theChosenFaceIsNamedOnEveryRunAndAnUnknownOneIsNot() throws Exception {
        var arial = new MeetingMinutesDocument.Heading("Tasco", "", "", "Giao ban", "", "", "", "", "", "", "",
                List.of(), "Arial");
        assertEquals(List.of("Arial"), faces(MeetingMinutesDocument.render(meeting(), arial)),
                "every run, the letterhead and the signatures included, is set in the face that was picked");

        var unknown = MeetingService.validate(new MeetingMinutesDocument.Heading("Tasco", "", "", "Giao ban", "", "",
                "", "", "", "", "", List.of(), "Comic Sans MS"));
        assertEquals(List.of("Times New Roman"), faces(MeetingMinutesDocument.render(meeting(), unknown)),
                "a face nobody offered falls back to the decree's own");
    }

    private static List<String> faces(byte[] bytes) throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var faces = new TreeSet<String>();
            document.getParagraphs().forEach(p -> p.getRuns().forEach(run -> faces.add(run.getFontFamily())));
            for (var table : document.getTables())
                for (var row : table.getRows())
                    for (var cell : row.getTableCells())
                        cell.getParagraphs().forEach(p -> p.getRuns().forEach(run -> faces.add(run.getFontFamily())));
            return List.copyOf(faces);
        }
    }

    private static boolean bordered(XWPFTable table) {
        var properties = table.getCTTbl().getTblPr();
        return properties != null && properties.isSetTblBorders();
    }

    private static Meeting.Detail meeting(Meeting.MinutesItem... items) {
        var minutes = new Meeting.Minutes(Meeting.MinutesStatus.READY, null,
                "Cuộc họp chốt ngân sách quý 4 trước thứ Năm.", "Giao ban tuần", Instant.parse("2026-09-21T03:15:00Z"),
                List.of(items).stream().filter(item -> item.kind() == Meeting.ItemKind.DECISION).toList(),
                List.of(items).stream().filter(item -> item.kind() == Meeting.ItemKind.ACTION).toList());
        return new Meeting.Detail(UUID.randomUUID(), "Giao ban tuần", Meeting.Kind.IN_PERSON, "vi",
                List.of("Anh Thanh", "Chị Lan"), List.of(), "", Meeting.Status.ENDED, "SONIOX", true,
                Instant.parse("2026-09-21T02:00:00Z"), Instant.parse("2026-09-21T03:15:00Z"), 3, List.of(), List.of(),
                minutes, new Meeting.Audio(Meeting.AudioStatus.NONE, null, null, 0, null), true, List.of());
    }

    /** Every paragraph of the document, including the ones inside the letterhead and signature tables. */
    private static List<String> read(byte[] bytes) throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var lines = new ArrayList<String>();
            document.getParagraphs().stream().map(XWPFParagraph::getText).forEach(lines::add);
            document.getTables().forEach(table -> table.getRows().forEach(row -> row.getTableCells()
                    .forEach(cell -> cell.getParagraphs().stream().map(XWPFParagraph::getText).forEach(lines::add))));
            return lines;
        }
    }
}
