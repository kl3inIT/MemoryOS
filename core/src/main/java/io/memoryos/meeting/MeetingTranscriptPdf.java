package io.memoryos.meeting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final Map<PDType0Font, Map<Integer, Boolean>> known = new HashMap<>();
        private PDPageContentStream stream;
        private float y;

        private Page(PDDocument document) throws IOException {
            this.document = document;
            this.regular = font(document, "HankenGrotesk-Regular.ttf");
            this.bold = font(document, "HankenGrotesk-Bold.ttf");
            start();
        }

        private static PDType0Font font(PDDocument document, String file) throws IOException {
            try (InputStream in = MeetingTranscriptPdf.class.getResourceAsStream("/fonts/" + file)) {
                if (in == null) throw new IOException("Missing font " + file);
                return PDType0Font.load(document, in, true);
            }
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
            float at = MARGIN + (width() - font.getStringWidth(safe) / 1000 * size) / 2;
            draw(safe, font, size, Math.max(MARGIN, at));
            y -= size + 4;
        }

        /** One line of the transcript: its time and speaker in bold, then what was said, wrapped to the page. */
        void line(String prefix, String text) throws IOException {
            String head = safe(prefix, bold);
            room(LEADING);
            float x = MARGIN;
            draw(head, bold, BODY, x);
            x += bold.getStringWidth(head) / 1000 * BODY;
            for (String piece : wrap(safe(text, regular), width() - (x - MARGIN))) {
                draw(piece, regular, BODY, x);
                y -= LEADING;
                room(LEADING);
                x = MARGIN;
            }
            if (text.isBlank()) y -= LEADING;
        }

        /** Greedy wrapping on spaces; a single word longer than the line is cut rather than run off the page. */
        private List<String> wrap(String text, float first) throws IOException {
            var lines = new ArrayList<String>();
            var current = new StringBuilder();
            float limit = first;
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (regular.getStringWidth(candidate) / 1000 * BODY <= limit) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                    limit = width();
                }
                while (regular.getStringWidth(word) / 1000 * BODY > limit && word.length() > 1) {
                    int cut = word.length();
                    while (cut > 1 && regular.getStringWidth(word.substring(0, cut)) / 1000 * BODY > limit) cut--;
                    lines.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                current.append(word);
            }
            lines.add(current.toString());
            return lines;
        }

        private void draw(String text, PDType0Font font, float size, float x) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();
        }

        /**
         * What was said is data: composed to NFC so Vietnamese marks use the font's own glyphs, and a character the
         * typeface lacks becomes a question mark instead of failing the download.
         */
        private String safe(String value, PDType0Font font) {
            String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
            var glyphs = known.computeIfAbsent(font, ignored -> new HashMap<>());
            var out = new StringBuilder(normalized.length());
            normalized.codePoints().forEach(point -> {
                boolean drawable = glyphs.computeIfAbsent(point, code -> {
                    try {
                        font.encode(new String(Character.toChars(code)));
                        return true;
                    } catch (IllegalArgumentException | IOException missing) {
                        return false;
                    }
                });
                out.append(drawable ? new String(Character.toChars(point)) : "?");
            });
            return out.toString();
        }

        void close() throws IOException {
            if (stream != null) stream.close();
            stream = null;
        }
    }
}
