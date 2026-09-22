package io.memoryos.meeting;

import io.memoryos.meeting.MeetingMinutesLayout.Align;
import io.memoryos.meeting.MeetingMinutesLayout.Cell;
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
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;

/**
 * Renders a meeting's minutes as a Vietnamese <i>biên bản</i> in Word format, following the layout of Nghị định
 * 30/2020/NĐ-CP mẫu 1.9 (quốc hiệu, tên cơ quan, thành phần, nội dung, kết luận, nhiệm vụ, chữ ký). The decree binds
 * state bodies; a company follows it by convention, so every heading field is the owner's to fill and an empty one is
 * left as an ellipsis for them to write on the printed page.
 *
 * <p>What the document says lives in {@link MeetingMinutesLayout}, shared with {@link MeetingMinutesPdf}; this class
 * only knows how Word draws it. No model runs here, and the same meeting and heading always produce the same document.
 */
public final class MeetingMinutesDocument {
    /** Nghị định 30 asks for Times New Roman at 13 to 14 points; body text is 13, the title 14. */
    static final String FONT = "Times New Roman";
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
                          String secretaryRole, List<String> attendees, String font) {
        public Heading {
            attendees = List.copyOf(attendees);
        }

        public Heading(String organization, String parentOrganization, String number, String about, String place,
                String opened, String closed, String chair, String chairRole, String secretary, String secretaryRole,
                List<String> attendees) {
            this(organization, parentOrganization, number, about, place, opened, closed, chair, chairRole, secretary,
                    secretaryRole, attendees, "");
        }

        /** What the document is set in. The decree asks for Times New Roman; a company is free to ask for its own. */
        public String typeface() {
            return font.isBlank() ? FONT : font;
        }
    }

    public static byte[] render(Meeting.Detail meeting, Heading heading) {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            defaultFont(document, heading.typeface());
            MeetingMinutesLayout.write(meeting, heading, new WordPage(document));
            typeface(document, heading.typeface());
            document.write(bytes);
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The layout's lines as Word paragraphs, its columns as a borderless two-cell table. */
    private record WordPage(XWPFDocument document) implements MeetingMinutesLayout.Page {
        @Override
        public void line(String text, boolean bold, int size, Align align) {
            paragraph(text, bold, size, align);
        }

        @Override
        public void indented(String text) {
            paragraph(text, false, MeetingMinutesLayout.BODY, Align.JUSTIFY).setIndentationFirstLine(TAB);
        }

        @Override
        public void listed(String text) {
            paragraph(text, false, MeetingMinutesLayout.BODY, Align.JUSTIFY).setIndentationLeft(LEVEL_1);
        }

        @Override
        public void bullet(String text) {
            paragraph("- " + text, false, MeetingMinutesLayout.BODY, Align.JUSTIFY).setIndentationLeft(TAB);
        }

        @Override
        public void blank() {
            document.createParagraph();
        }

        @Override
        public void columns(List<Cell> left, List<Cell> right, boolean keepWithPrevious) {
            var table = document.createTable(1, 2);
            table.setWidth("100%");
            var properties = table.getCTTbl().getTblPr();
            if (properties != null && properties.isSetTblBorders()) properties.unsetTblBorders();
            fill(table.getRow(0).getCell(0), left);
            fill(table.getRow(0).getCell(1), right);
        }

        private XWPFParagraph paragraph(String text, boolean bold, int size, Align align) {
            var paragraph = document.createParagraph();
            paragraph.setAlignment(alignment(align));
            run(paragraph, text, bold, size);
            return paragraph;
        }

        private static void fill(XWPFTableCell cell, List<Cell> lines) {
            for (var line : lines) {
                // A new cell already carries one empty paragraph; the first line reuses it.
                var paragraph = cell.getParagraphs().size() == 1 && cell.getParagraphs().getFirst().getRuns().isEmpty()
                        ? cell.getParagraphs().getFirst() : cell.addParagraph();
                paragraph.setAlignment(ParagraphAlignment.CENTER);
                run(paragraph, line.text(), line.bold(), line.size());
            }
        }
    }

    private static ParagraphAlignment alignment(Align align) {
        return switch (align) {
            case LEFT -> ParagraphAlignment.LEFT;
            case CENTER -> ParagraphAlignment.CENTER;
            case JUSTIFY -> ParagraphAlignment.BOTH;
        };
    }

    private static void defaultFont(XWPFDocument document, String face) {
        var fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(face);
        fonts.setHAnsi(face);
        document.createStyles().setDefaultFonts(fonts);
    }

    /**
     * Names the chosen face on every run, including the ones inside the letterhead and signature tables. The document
     * default already carries it, but not every reader honours that — LibreOffice falls back to its own — and a
     * biên bản that changes typeface when somebody else opens it is not the one that was signed.
     */
    private static void typeface(XWPFDocument document, String face) {
        for (var paragraph : document.getParagraphs()) paragraph.getRuns().forEach(run -> run.setFontFamily(face));
        for (XWPFTable table : document.getTables())
            for (var row : table.getRows())
                for (var cell : row.getTableCells())
                    for (var paragraph : cell.getParagraphs())
                        paragraph.getRuns().forEach(run -> run.setFontFamily(face));
    }

    private static XWPFRun run(XWPFParagraph paragraph, String text, boolean bold, int size) {
        var run = paragraph.createRun();
        run.setFontSize(size / 2.0);
        run.setBold(bold);
        run.setText(text);
        return run;
    }
}
