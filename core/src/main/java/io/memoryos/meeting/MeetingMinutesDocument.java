package io.memoryos.meeting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.jspecify.annotations.Nullable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;

/**
 * Renders a meeting's minutes as a Vietnamese <i>biên bản</i> in Word format, following the layout of Nghị định
 * 30/2020/NĐ-CP mẫu 1.9 (quốc hiệu, tên cơ quan, thành phần, nội dung, kết luận, nhiệm vụ, chữ ký). The decree binds
 * state bodies; a company follows it by convention, so every heading field is the owner's to fill and an empty one is
 * left as an ellipsis for them to write on the printed page.
 *
 * <p>No model runs here. The same meeting and the same heading always produce the same document.
 */
public final class MeetingMinutesDocument {
    /** Nghị định 30 asks for Times New Roman at 13 to 14 points; body text is 13, the title 14. */
    private static final String FONT = "Times New Roman";
    private static final int BODY = 26;
    private static final int TITLE = 28;
    private static final int SMALL = 22;
    private static final String BLANK = "…";
    /** Twips: the first-line indent and the two levels of list indent the decree's sample uses. */
    private static final int TAB = 720;
    private static final int LEVEL_1 = 360;

    private MeetingMinutesDocument() {}

    /**
     * What the transcript cannot supply. The owner fills these in before exporting; the web pre-fills what the meeting
     * already knows (its title, its participants and when it ran).
     */
    public record Heading(String organization, String parentOrganization, String number, String about, String place,
                          String opened, String closed, String chair, String chairRole, String secretary,
                          String secretaryRole, List<String> attendees) {
        public Heading {
            attendees = List.copyOf(attendees);
        }
    }

    public static byte[] render(Meeting.Detail meeting, Heading heading) {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            defaultFont(document);
            letterhead(document, heading);
            title(document, heading);
            opening(document, heading);
            attendees(document, heading);
            content(document, meeting, heading);
            signatures(document, heading);
            document.write(bytes);
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The two-column letterhead: the body on the left, the national heading on the right. */
    private static void letterhead(XWPFDocument document, Heading heading) {
        var table = borderless(document, 2);
        var left = table.getRow(0).getCell(0);
        cellLine(left, or(heading.parentOrganization(), ""), true, BODY, ParagraphAlignment.CENTER);
        cellLine(left, or(heading.organization(), BLANK), true, BODY, ParagraphAlignment.CENTER);
        cellLine(left, "Số: " + or(heading.number(), BLANK) + "/BB", false, BODY, ParagraphAlignment.CENTER);
        var right = table.getRow(0).getCell(1);
        cellLine(right, "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", true, BODY, ParagraphAlignment.CENTER);
        cellLine(right, "Độc lập - Tự do - Hạnh phúc", true, BODY, ParagraphAlignment.CENTER);
    }

    private static void title(XWPFDocument document, Heading heading) {
        blank(document);
        line(document, "BIÊN BẢN", true, TITLE, ParagraphAlignment.CENTER);
        line(document, "Về việc " + or(heading.about(), BLANK), true, BODY, ParagraphAlignment.CENTER);
        blank(document);
    }

    private static void opening(XWPFDocument document, Heading heading) {
        indented(document, "Hôm nay, vào lúc " + or(heading.opened(), BLANK));
        indented(document, "Tại " + or(heading.place(), BLANK));
        indented(document, "Diễn ra cuộc họp với nội dung " + or(heading.about(), BLANK));
        blank(document);
    }

    private static void attendees(XWPFDocument document, Heading heading) {
        line(document, "I. Thành phần tham dự:", true, BODY, ParagraphAlignment.LEFT);
        listed(document, "1. Chủ trì: " + person(heading.chair(), heading.chairRole()));
        listed(document, "2. Thư ký: " + person(heading.secretary(), heading.secretaryRole()));
        listed(document, "3. Thành phần khác:");
        if (heading.attendees().isEmpty()) bullet(document, BLANK);
        else heading.attendees().forEach(attendee -> bullet(document, attendee));
        blank(document);
    }

    private static void content(XWPFDocument document, Meeting.Detail meeting, Heading heading) {
        var minutes = meeting.minutes();
        line(document, "II. Nội dung cuộc họp:", true, BODY, ParagraphAlignment.LEFT);
        paragraphs(document, minutes.summary());
        blank(document);

        line(document, "III. Kết luận cuộc họp:", true, BODY, ParagraphAlignment.LEFT);
        if (minutes.decisions().isEmpty()) listed(document, BLANK);
        else numbered(document, minutes.decisions().stream().map(Meeting.MinutesItem::text).toList());
        blank(document);

        line(document, "IV. Nhiệm vụ được giao:", true, BODY, ParagraphAlignment.LEFT);
        if (minutes.actions().isEmpty()) listed(document, BLANK);
        else numbered(document, minutes.actions().stream().map(MeetingMinutesDocument::assignment).toList());
        blank(document);

        indented(document, "Cuộc họp kết thúc vào lúc " + or(heading.closed(), BLANK)
                + ", nội dung cuộc họp đã được các thành viên dự họp thông qua và cùng ký vào biên bản./.");
        blank(document);
    }

    /** "Kiểm tra bảng cân đối do Anh Minh thực hiện, thời hạn thứ Tư." */
    private static String assignment(Meeting.MinutesItem action) {
        var sentence = new StringBuilder(action.text());
        if (action.owner() != null) sentence.append(" do ").append(action.owner()).append(" thực hiện");
        if (action.due() != null) sentence.append(", thời hạn ").append(action.due());
        if (sentence.charAt(sentence.length() - 1) != '.') sentence.append('.');
        return sentence.toString();
    }

    private static void signatures(XWPFDocument document, Heading heading) {
        var table = borderless(document, 2);
        var secretary = table.getRow(0).getCell(0);
        cellLine(secretary, "THƯ KÝ", true, BODY, ParagraphAlignment.CENTER);
        cellLine(secretary, "(Ký, ghi rõ họ tên)", false, SMALL, ParagraphAlignment.CENTER);
        cellLine(secretary, "", false, BODY, ParagraphAlignment.CENTER);
        cellLine(secretary, "", false, BODY, ParagraphAlignment.CENTER);
        cellLine(secretary, or(heading.secretary(), BLANK), true, BODY, ParagraphAlignment.CENTER);
        var chair = table.getRow(0).getCell(1);
        cellLine(chair, "CHỦ TỌA", true, BODY, ParagraphAlignment.CENTER);
        cellLine(chair, "(Ký, ghi rõ họ tên)", false, SMALL, ParagraphAlignment.CENTER);
        cellLine(chair, "", false, BODY, ParagraphAlignment.CENTER);
        cellLine(chair, "", false, BODY, ParagraphAlignment.CENTER);
        cellLine(chair, or(heading.chair(), BLANK), true, BODY, ParagraphAlignment.CENTER);
    }

    private static String person(@Nullable String name, @Nullable String role) {
        String who = "Ông/Bà " + or(name, BLANK);
        return isBlank(role) ? who : who + " - Chức vụ: " + role.strip();
    }

    private static void paragraphs(XWPFDocument document, String text) {
        if (isBlank(text)) {
            listed(document, BLANK);
            return;
        }
        text.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(line -> listed(document, line));
    }

    private static void numbered(XWPFDocument document, List<String> items) {
        int position = 1;
        for (var item : items) listed(document, position++ + ". " + item);
    }

    // Paragraph and run plumbing. Every run goes through run(), so the decree's font and sizes live in one place.

    private static void defaultFont(XWPFDocument document) {
        var fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(FONT);
        fonts.setHAnsi(FONT);
        document.createStyles().setDefaultFonts(fonts);
    }

    private static XWPFParagraph line(XWPFDocument document, String text, boolean bold, int size,
                                      ParagraphAlignment alignment) {
        var paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        run(paragraph, text, bold, size);
        return paragraph;
    }

    /** A body paragraph with the decree's first-line indent. */
    private static void indented(XWPFDocument document, String text) {
        var paragraph = line(document, text, false, BODY, ParagraphAlignment.BOTH);
        paragraph.setIndentationFirstLine(TAB);
    }

    /** One item under a numbered section. */
    private static void listed(XWPFDocument document, String text) {
        var paragraph = line(document, text, false, BODY, ParagraphAlignment.BOTH);
        paragraph.setIndentationLeft(LEVEL_1);
    }

    private static void bullet(XWPFDocument document, String text) {
        var paragraph = line(document, "- " + text, false, BODY, ParagraphAlignment.BOTH);
        paragraph.setIndentationLeft(TAB);
    }

    private static void blank(XWPFDocument document) {
        document.createParagraph();
    }

    private static void cellLine(XWPFTableCell cell, String text, boolean bold, int size, ParagraphAlignment alignment) {
        // A new cell already carries one empty paragraph; the first line reuses it.
        var paragraph = cell.getParagraphs().size() == 1 && cell.getParagraphs().getFirst().getRuns().isEmpty()
                ? cell.getParagraphs().getFirst() : cell.addParagraph();
        paragraph.setAlignment(alignment);
        run(paragraph, text, bold, size);
    }

    private static XWPFRun run(XWPFParagraph paragraph, String text, boolean bold, int size) {
        var run = paragraph.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(size / 2.0);
        run.setBold(bold);
        run.setText(text);
        return run;
    }

    private static XWPFTable borderless(XWPFDocument document, int columns) {
        var table = document.createTable(1, columns);
        table.setWidth("100%");
        var properties = table.getCTTbl().getTblPr();
        if (properties != null && properties.isSetTblBorders()) properties.unsetTblBorders();
        return table;
    }

    private static String or(@Nullable String value, String fallback) {
        return isBlank(value) ? fallback : value.strip();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
