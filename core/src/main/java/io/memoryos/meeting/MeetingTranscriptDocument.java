package io.memoryos.meeting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;

/**
 * The transcript as a Word file: what was said, by whom, at what time, and nothing else. It is not a
 * <i>biên bản</i> — {@link MeetingMinutesDocument} writes that — and it is deliberately plain, because somebody
 * sending a record of what was said should be sending the record and not a presentation of it.
 *
 * <p>No model runs here. The same meeting always produces the same document.
 */
public final class MeetingTranscriptDocument {
    private static final String FONT = "Times New Roman";
    /** Half-points: 13 for the body, as the minutes use, and 15 for the one title. */
    private static final int BODY = 26;
    private static final int TITLE = 30;
    private static final int SMALL = 22;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);

    private MeetingTranscriptDocument() {}

    public static byte[] render(Meeting.Detail meeting) {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            defaultFont(document);
            var title = document.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            run(title, meeting.title(), TITLE, true);

            var when = document.createParagraph();
            when.setAlignment(ParagraphAlignment.CENTER);
            run(when, WHEN.format(meeting.createdAt()) + " UTC", SMALL, false);
            if (!meeting.participants().isEmpty()) {
                var people = document.createParagraph();
                people.setAlignment(ParagraphAlignment.CENTER);
                run(people, String.join(", ", meeting.participants()), SMALL, false);
            }

            for (var line : MeetingTranscript.lines(meeting)) {
                var paragraph = document.createParagraph();
                paragraph.setSpacingAfter(120);
                run(paragraph, "[" + line.time() + "] " + line.speaker() + ": ", BODY, true);
                run(paragraph, line.text(), BODY, false);
            }
            document.write(bytes);
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void run(XWPFParagraph paragraph, String text, int size, boolean bold) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT);
        run.setFontSize(size / 2.0);
        run.setBold(bold);
        run.setText(text);
    }

    /** Word falls back to its own default for any run that does not name a font, including empty ones. */
    private static void defaultFont(XWPFDocument document) {
        var fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(FONT);
        fonts.setHAnsi(FONT);
        document.createStyles().setDefaultFonts(fonts);
    }
}
