package io.memoryos.meeting;

import io.memoryos.meeting.MeetingMinutesLayout.Align;
import io.memoryos.meeting.MeetingMinutesLayout.Cell;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.memoryos.shared.PdfText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The same biên bản as {@link MeetingMinutesDocument}, as a PDF for reading and printing rather than editing.
 *
 * <p>A PDF has to carry its typeface, and the faces a company picks — Times New Roman, Arial, Calibri — are licensed and
 * cannot ship. Each is set in the open face drawn to the same metrics, so a line breaks where it would in Word: Tinos
 * for Times New Roman, Arimo for Arial and Tahoma, Carlito for Calibri. Text is composed to NFC so Vietnamese marks
 * land on the face's own glyphs, and a character the face lacks becomes a question mark rather than failing the file.
 *
 * <p>Margins follow Nghị định 30: 30 mm on the left for binding, 15 mm on the right, 20 mm top and bottom.
 */
public final class MeetingMinutesPdf {
    private static final float MM = 72f / 25.4f;
    private static final float LEFT = 30 * MM;
    private static final float RIGHT = 15 * MM;
    private static final float TOP = 20 * MM;
    private static final float BOTTOM = 20 * MM;
    /** Points: the same indents as the Word file's 720 and 360 twips. */
    private static final float TAB = 36;
    private static final float LEVEL_1 = 18;
    private static final float LEADING = 1.3f;
    private static final Map<String, String> FACES = Map.of(
            "Times New Roman", "Tinos", "Arial", "Arimo", "Tahoma", "Arimo", "Calibri", "Carlito");

    private MeetingMinutesPdf() {}

    public static byte[] render(Meeting.Detail meeting, MeetingMinutesDocument.Heading heading) {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            String family = FACES.getOrDefault(heading.typeface(), "Tinos");
            var page = new PdfPage(document, family);
            MeetingMinutesLayout.write(meeting, heading, page);
            page.draw();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A measured piece of the document, laid out before anything is drawn so a page break can look back. */
    private sealed interface Block permits Lines, Columns, Gap {
        float height();
    }

    /** One paragraph, already broken into lines that fit. */
    private record Lines(List<Line> lines, float size, float leading, boolean bold) implements Block {
        public float height() {
            return lines.size() * leading;
        }
    }

    /** A line of a paragraph: where it starts, and whether its words are spread to the right margin. */
    private record Line(List<String> words, float x, float width, boolean justify, boolean centre) {}

    private record Columns(List<Cell> left, List<Cell> right, float leftShare, float height, boolean keepWithPrevious)
            implements Block {}

    private record Gap(float height) implements Block {}

    /** Collects the layout as measured blocks, then paginates and draws them. */
    private static final class PdfPage implements MeetingMinutesLayout.Page {
        private final PDDocument document;
        private final PdfText text;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final List<Block> blocks = new ArrayList<>();
        private final float width = PDRectangle.A4.getWidth() - LEFT - RIGHT;

        private PdfPage(PDDocument document, String family) throws IOException {
            this.document = document;
            this.text = new PdfText(document);
            this.regular = text.font(family + "-Regular.ttf");
            this.bold = text.font(family + "-Bold.ttf");
        }

        @Override
        public void line(String text, boolean bold, int size, Align align) {
            paragraph(text, bold, size, align, 0, 0);
        }

        @Override
        public void indented(String text) {
            paragraph(text, false, MeetingMinutesLayout.BODY, Align.JUSTIFY, 0, TAB);
        }

        @Override
        public void listed(String text) {
            paragraph(text, false, MeetingMinutesLayout.BODY, Align.JUSTIFY, LEVEL_1, 0);
        }

        @Override
        public void bullet(String text) {
            paragraph("- " + text, false, MeetingMinutesLayout.BODY, Align.JUSTIFY, TAB, 0);
        }

        @Override
        public void blank() {
            blocks.add(new Gap(points(MeetingMinutesLayout.BODY) * LEADING));
        }

        @Override
        public void columns(List<Cell> left, List<Cell> right, float leftShare, boolean keepWithPrevious) {
            float height = Math.max(columnHeight(left, width * leftShare), columnHeight(right, width * (1 - leftShare)));
            blocks.add(new Columns(left, right, leftShare, height, keepWithPrevious));
        }

        private float columnHeight(List<Cell> cells, float columnWidth) {
            float height = 0;
            for (var cell : cells)
                height += wrap(safe(cell.text(), cell.bold() ? bold : regular), cell.bold() ? bold : regular,
                        points(cell.size()), columnWidth, columnWidth).size() * points(cell.size()) * LEADING;
            return height;
        }

        private void paragraph(String text, boolean isBold, int halfPoints, Align align, float left, float first) {
            var face = isBold ? bold : regular;
            float size = points(halfPoints);
            var broken = wrap(safe(text, face), face, size, width - left - first, width - left);
            var lines = new ArrayList<Line>(broken.size());
            for (int i = 0; i < broken.size(); i++) {
                float x = LEFT + left + (i == 0 ? first : 0);
                float available = width - left - (i == 0 ? first : 0);
                boolean last = i == broken.size() - 1;
                lines.add(new Line(broken.get(i), x, available, align == Align.JUSTIFY && !last,
                        align == Align.CENTER));
            }
            blocks.add(new Lines(lines, size, size * LEADING, isBold));
        }

        /**
         * Pages the blocks top to bottom. A block marked to keep with the previous one never opens a page alone: the
         * paragraph before it — and the gap between them — moves over with it.
         */
        void draw() throws IOException {
            float top = PDRectangle.A4.getHeight() - TOP;
            float room = top - BOTTOM;
            var pages = new ArrayList<List<Block>>();
            var current = new ArrayList<Block>();
            float used = 0;
            for (var block : blocks) {
                // A gap that does not fit is not carried to the next page: it would only push the next block down,
                // and it must never be what breaks the page ahead of a block that has to stay with its paragraph.
                if (block instanceof Gap && used + block.height() > room) continue;
                if (used + block.height() > room && !current.isEmpty()) {
                    var carried = new ArrayList<Block>();
                    if (block instanceof Columns columns && columns.keepWithPrevious()) {
                        // Pull back the paragraph above, with any gap between, so the signatures sign something.
                        while (!current.isEmpty() && current.getLast() instanceof Gap) carried.addFirst(current.removeLast());
                        if (!current.isEmpty() && current.size() > 1) carried.addFirst(current.removeLast());
                    }
                    pages.add(current);
                    current = carried;
                    used = 0;
                    for (var moved : carried) used += moved.height();
                }
                current.add(block);
                used += block.height();
            }
            if (!current.isEmpty()) pages.add(current);

            for (var blocksOnPage : pages) {
                var page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    float y = top;
                    for (var block : blocksOnPage) {
                        switch (block) {
                            case Gap gap -> y -= gap.height();
                            case Lines lines -> {
                                for (var line : lines.lines()) {
                                    y -= lines.leading();
                                    text(stream, line, lines.bold() ? bold : regular, lines.size(), y + lines.leading()
                                            - lines.size());
                                }
                            }
                            case Columns columns -> {
                                float split = width * columns.leftShare();
                                column(stream, columns.left(), LEFT, split, y);
                                column(stream, columns.right(), LEFT + split, width - split, y);
                                y -= columns.height();
                            }
                        }
                    }
                }
            }
        }

        private void column(PDPageContentStream stream, List<Cell> cells, float x, float columnWidth, float top)
                throws IOException {
            float y = top;
            for (var cell : cells) {
                var face = cell.bold() ? bold : regular;
                float size = points(cell.size());
                for (var words : wrap(safe(cell.text(), face), face, size, columnWidth, columnWidth)) {
                    y -= size * LEADING;
                    float baseline = y + size * LEADING - size;
                    text(stream, new Line(words, x, columnWidth, false, true), face, size, baseline);
                    if (cell.underline() && !words.isEmpty())
                        underline(stream, words, face, size, x, columnWidth, baseline);
                }
            }
        }

        /** A rule as long as the centred line above it, as the decree draws under the tiêu ngữ. */
        private static void underline(PDPageContentStream stream, List<String> words, PDType0Font face, float size,
                float x, float columnWidth, float baseline) throws IOException {
            float length = face.getStringWidth(String.join(" ", words)) / 1000 * size;
            float start = x + (columnWidth - length) / 2;
            stream.setLineWidth(0.6f);
            stream.moveTo(start, baseline - 2.5f);
            stream.lineTo(start + length, baseline - 2.5f);
            stream.stroke();
        }

        private void text(PDPageContentStream stream, Line line, PDType0Font face, float size, float baseline)
                throws IOException {
            if (line.words().isEmpty()) return;
            float space = face.getStringWidth(" ") / 1000 * size;
            float words = 0;
            for (var word : line.words()) words += face.getStringWidth(word) / 1000 * size;
            float natural = words + space * (line.words().size() - 1);
            float gap = line.justify() && line.words().size() > 1
                    ? (line.width() - words) / (line.words().size() - 1) : space;
            float x = line.centre() ? line.x() + (line.width() - natural) / 2 : line.x();
            // Word by word, because a Type 0 face ignores the word-spacing operator a justified line would need.
            for (var word : line.words()) {
                stream.beginText();
                stream.setFont(face, size);
                stream.newLineAtOffset(x, baseline);
                stream.showText(word);
                stream.endText();
                x += face.getStringWidth(word) / 1000 * size + gap;
            }
        }

        private static List<List<String>> wrap(String text, PDType0Font face, float size, float first, float rest) {
            return PdfText.words(text, face, size, first, rest);
        }

        private String safe(String value, PDType0Font face) {
            return text.safe(value, face);
        }

        private static float points(int halfPoints) {
            return halfPoints / 2f;
        }
    }
}
