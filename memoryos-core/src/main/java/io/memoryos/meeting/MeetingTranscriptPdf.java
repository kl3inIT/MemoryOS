package io.memoryos.meeting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import io.memoryos.shared.PdfText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The same transcript as a PDF, for people who want to read it rather than edit it.
 *
 * <p>The built-in PDF typefaces cannot draw Vietnamese, so the bundled Hanken Grotesk is embedded, text is composed
 * to NFC so its marks land on the precomposed glyphs, and a character the typeface does not have becomes a question
 * mark rather than failing the whole download — the same rule the usage report follows.
 */
public final class MeetingTranscriptPdf {
    private static final float MARGIN = 56;
    private static final float BODY = 10.5f;
    private static final float TITLE = 16;
    private static final float SMALL = 9;
    private static final float LEADING = 14;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);

    private MeetingTranscriptPdf() {}

    public static byte[] render(Meeting.Detail meeting) {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new Page(document);
            var regular = page.regular;
            var bold = page.bold;
            page.centred(meeting.title(), bold, TITLE);
            page.centred(WHEN.format(meeting.createdAt()) + " UTC", regular, SMALL);
            if (!meeting.participants().isEmpty())
                page.centred(String.join(", ", meeting.participants()), regular, SMALL);
            page.gap();
            for (var line : MeetingTranscript.lines(meeting))
                page.line("[" + line.time() + "] " + line.speaker() + ": ", line.text());
            page.close();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** One document's worth of pages, written top to bottom. */
    private static final class Page {
        private final PDDocument document;
        private final PdfText text;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private PDPageContentStream stream;
        private float y;

        private Page(PDDocument document) throws IOException {
            this.document = document;
            this.text = new PdfText(document);
            this.regular = text.font("HankenGrotesk-Regular.ttf");
            this.bold = text.font("HankenGrotesk-Bold.ttf");
            start();
        }

        private void start() throws IOException {
            if (stream != null) stream.close();
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        private float width() {
            return PDRectangle.A4.getWidth() - 2 * MARGIN;
        }

        private void room(float needed) throws IOException {
            if (y - needed < MARGIN) start();
        }

        void gap() {
            y -= LEADING;
        }

        void centred(String text, PDType0Font font, float size) throws IOException {
            String safe = safe(text, font);
            room(size + LEADING);
            float at = MARGIN + (width() - PdfText.width(safe, font, size)) / 2;
            draw(safe, font, size, Math.max(MARGIN, at));
            y -= size + 4;
        }

        /** One line of the transcript: its time and speaker in bold, then what was said, wrapped to the page. */
        void line(String prefix, String said) throws IOException {
            String head = safe(prefix, bold);
            room(LEADING);
            float x = MARGIN;
            draw(head, bold, BODY, x);
            x += PdfText.width(head, bold, BODY);
            for (String piece : PdfText.lines(safe(said, regular), regular, BODY, width() - (x - MARGIN), width())) {
                draw(piece, regular, BODY, x);
                y -= LEADING;
                room(LEADING);
                x = MARGIN;
            }
            if (said.isBlank()) y -= LEADING;
        }

        private void draw(String text, PDType0Font font, float size, float x) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();
        }

        private String safe(String value, PDType0Font font) {
            return text.safe(value, font);
        }

        void close() throws IOException {
            if (stream != null) stream.close();
            stream = null;
        }
    }
}
