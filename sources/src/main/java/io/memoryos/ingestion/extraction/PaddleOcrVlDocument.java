package io.memoryos.ingestion.extraction;

import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.BoundingBox;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.CoordOrigin;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractedDocument.Table;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.document.StructuredContent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The PaddleOCR-VL adapter: {@code layoutParsingResults} to MemoryOS blocks. No Paddle key survives.
 *
 * <p>Each result is one rendered page, and {@code parsing_res_list} is already in reading order.
 * A block's {@code block_bbox} is in pixels of that rendering, which is the page's CropBox. Scaled
 * by the CropBox size and moved to its corner, it becomes a box in PDF user space with a
 * bottom-left origin, the space the viewer's page {@code view} is in. A page turned by
 * {@code /Rotate} carries its page number alone: the viewer outlines no rotated page, and an
 * unlocated passage is never drawn. Nor does an image input, which has no page in points.
 *
 * <p>A {@code doc_title} is a level-1 heading: in the reports it names a part (the disclosure cover,
 * the notes). A {@code paragraph_title} is level 2: a note's title above its table. A
 * {@code figure_title} is level 2 only when it names the table under it (a statement's title, a
 * note title Paddle took for a caption), so a chunk's section reads "… > BÁO CÁO TÌNH HÌNH TÀI
 * CHÍNH"; any other figure title stays a paragraph.
 */
final class PaddleOcrVlDocument {
    /** Running headers and footers, page numbers and margin notes, left out of the body as Docling does. */
    private static final Set<String> FURNITURE = Set.of(
            "header", "footer", "number", "header_image", "footer_image", "aside_text");
    private static final Set<String> PICTURES = Set.of("image", "chart", "seal");
    private static final int MAX_COLUMNS = 1_000;
    /** Blocks whose text a later figure title is compared with: titles and the running page header. */
    private static final Set<String> TITLES = Set.of("doc_title", "paragraph_title", "figure_title", "header");
    /** Word overlap at which a figure title repeats an earlier title: continuations measured 0.82 and up. */
    private static final double REPEATED_TITLE = 0.75;
    /**
     * A table's value: an amount ({@code 1.234.567}, {@code (8.910.000.000)}, {@code 802.373,22}), a
     * percentage, a line-item code ({@code 01}, {@code 111}) or note reference ({@code V.1}), or a dash
     * for nil. A header never is one; {@code 2025}, {@code 31/03/2026} and {@code Quý 1.2026} are not.
     */
    private static final Pattern VALUE = Pattern.compile("\\(?-?\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d+)?\\)?"
            + "|\\d{1,3}(?:[.,]\\d+)?%|\\d{2,3}|[IVX]+\\.\\d+(?:\\.\\d+)*|[-\\u2013]");
    private static final Pattern WORD = Pattern.compile("[a-z]{2,}");
    private static final int MAX_HEADER_ROWS = 3;

    private PaddleOcrVlDocument() {}

    /**
     * @param pages the PDF's page frames, one per result; empty for an image input, where each
     *        result is one frame (a multi-page TIFF has several) and carries a page number but no box
     */
    static List<Block> blocks(JsonNode results, List<PdfLayout.Frame> pages) throws ExtractionException {
        if (!pages.isEmpty() && results.size() != pages.size()) throw failure(ExtractionFailure.MALFORMED);
        if (results.isEmpty()) throw failure(ExtractionFailure.MALFORMED);
        var blocks = new ArrayList<Block>();
        int[] cells = {0};
        // Every title and running header read so far, as word sets: a figure title that repeats one is a
        // running page title, not the name of the table under it.
        var titles = new ArrayList<Set<String>>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode page = results.get(index).path("prunedResult");
            JsonNode items = page.path("parsing_res_list");
            if (!items.isArray()) throw failure(ExtractionFailure.MALFORMED);
            PdfLayout.Frame frame = null;
            double scaleX = 0;
            double scaleY = 0;
            if (!pages.isEmpty()) {
                JsonNode width = page.path("width");
                JsonNode height = page.path("height");
                if (!width.isNumber() || !height.isNumber() || width.asDouble() <= 0 || height.asDouble() <= 0) {
                    throw failure(ExtractionFailure.MALFORMED);
                }
                if (pages.get(index).rotation() == 0) {
                    frame = pages.get(index);
                    scaleX = frame.width() / width.asDouble();
                    scaleY = frame.height() / height.asDouble();
                }
            }
            for (int at = 0; at < items.size(); at++) {
                JsonNode item = items.get(at);
                String label = item.path("block_label").asString("");
                boolean tableTitle = "figure_title".equals(label) && namesTheTableBelow(items, at, titles);
                if (TITLES.contains(label)) titles.add(words(item.path("block_content").asString("")));
                if (FURNITURE.contains(label)) continue;
                if (blocks.size() >= StructuredContent.MAX_BLOCKS) throw failure(ExtractionFailure.WRITE_LIMIT);
                String content = item.path("block_content").asString("").strip();
                var locations = List.of(Location.page(index + 1,
                        frame == null ? null : box(item.path("block_bbox"), frame, scaleX, scaleY)));
                int position = blocks.size();
                if (PICTURES.contains(label)) {
                    blocks.add(new Block(position, Kind.IMAGE, "", null, locations, null, null, null));
                } else if ("table".equals(label)) {
                    // PaddleX keeps its raw recognition when it cannot build table cells from it; that
                    // text is still the page's content, so it is kept as a paragraph.
                    var table = table(content, cells);
                    if (!table.cells().isEmpty()) {
                        blocks.add(Block.table(position, "", locations, table, null));
                    } else {
                        String text = Jsoup.parse(content).text().strip();
                        if (!text.isEmpty()) blocks.add(Block.text(position, Kind.PARAGRAPH, text, locations));
                    }
                } else if (content.isEmpty()) {
                    continue;
                } else if ("doc_title".equals(label)) {
                    blocks.add(Block.heading(position, content, 1, locations));
                } else if ("paragraph_title".equals(label) || tableTitle) {
                    blocks.add(Block.heading(position, content, 2, locations));
                } else {
                    blocks.add(Block.text(position, Kind.PARAGRAPH, content, locations));
                }
            }
        }
        return blocks;
    }

    /**
     * {@code [x1, y1, x2, y2]} in pixels from the rendering's top left, as an array or as its string
     * form; anything else has no box. The result is in user space: x from the CropBox's left edge,
     * y up from its bottom edge.
     */
    private static @Nullable BoundingBox box(JsonNode bbox, PdfLayout.Frame frame, double scaleX, double scaleY) {
        double[] values = new double[4];
        if (bbox.isArray() && bbox.size() == 4) {
            for (int i = 0; i < 4; i++) {
                if (!bbox.get(i).isNumber()) return null;
                values[i] = bbox.get(i).asDouble();
            }
        } else if (bbox.isString()) {
            String[] parts = bbox.asString().replaceAll("[\\[\\]\\s]", "").split(",");
            if (parts.length != 4) return null;
            try {
                for (int i = 0; i < 4; i++) values[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException invalid) {
                return null;
            }
        } else {
            return null;
        }
        for (double value : values) if (!Double.isFinite(value)) return null;
        double top = frame.y0() + frame.height();
        return new BoundingBox(frame.x0() + values[0] * scaleX, top - values[1] * scaleY,
                frame.x0() + values[2] * scaleX, top - values[3] * scaleY, CoordOrigin.BOTTOMLEFT);
    }

    /**
     * A figure title names the table under it when the next body block on its page, past a unit note,
     * is that table, and it repeats no earlier title or running header of the document. Paddle labels
     * statement titles, note titles and running page titles alike as {@code figure_title}; a running
     * title repeats, a continuation page's "(tiếp theo)" title repeats the first page's.
     */
    private static boolean namesTheTableBelow(JsonNode items, int at, List<Set<String>> titles) {
        for (int next = at + 1; next < items.size(); next++) {
            String label = items.get(next).path("block_label").asString("");
            if (FURNITURE.contains(label) || "vision_footnote".equals(label)) continue;
            if (!"table".equals(label)) return false;
            var words = words(items.get(at).path("block_content").asString(""));
            if (words.isEmpty()) return false;
            for (var earlier : titles) {
                long shared = words.stream().filter(earlier::contains).count();
                if (shared >= REPEATED_TITLE * (words.size() + earlier.size() - shared)) return false;
            }
            return true;
        }
        return false;
    }

    /** Lower-case words of two letters or more, without diacritics, so OCR tone slips still match. */
    private static Set<String> words(String text) {
        String plain = Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        var words = new HashSet<String>();
        for (var matcher = WORD.matcher(plain); matcher.find(); ) words.add(matcher.group());
        return words;
    }

    /**
     * An HTML table laid out as a browser would: a cell goes to the first column its row has free,
     * so a cell under a {@code rowspan} moves right. Paddle writes only {@code <td>}; its column
     * headers are the cells {@link #columnHeaders} finds, and a table it is unsure of has none.
     */
    static Table table(String html, int[] cellCount) throws ExtractionException {
        Element table = Jsoup.parseBodyFragment(html).selectFirst("table");
        if (table == null) return new Table(0, 0, List.of());
        var rows = table.select("> tr, > thead > tr, > tbody > tr, > tfoot > tr");
        var occupied = new ArrayList<BitSet>(rows.size());
        for (int row = 0; row < rows.size(); row++) occupied.add(new BitSet());
        var laid = new ArrayList<Laid>();
        int columns = 0;
        for (int row = 0; row < rows.size(); row++) {
            int column = 0;
            for (Element element : rows.get(row).children()) {
                if (!element.is("td, th")) continue;
                if (++cellCount[0] > StructuredContent.MAX_CELLS) throw failure(ExtractionFailure.WRITE_LIMIT);
                column = occupied.get(row).nextClearBit(column);
                int rowSpan = Math.min(span(element, "rowspan"), rows.size() - row);
                int columnSpan = span(element, "colspan");
                if (column + columnSpan > MAX_COLUMNS) throw failure(ExtractionFailure.WRITE_LIMIT);
                for (int covered = row; covered < row + rowSpan; covered++) {
                    occupied.get(covered).set(column, column + columnSpan);
                }
                laid.add(new Laid(row, column, rowSpan, columnSpan, element.text().strip()));
                column += columnSpan;
                columns = Math.max(columns, column);
            }
        }
        var headers = columnHeaders(laid, rows.size());
        var cells = new ArrayList<Cell>(laid.size());
        for (var cell : laid) {
            cells.add(new Cell(cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan(), headers.contains(cell),
                    false, cell.text(), List.of()));
        }
        return new Table(rows.size(), columns, cells);
    }

    /** One laid-out cell before it is known to be a header. */
    private record Laid(int row, int column, int rowSpan, int columnSpan, String text) {
        boolean filled() { return !text.isBlank(); }
        int end() { return column + columnSpan; }
        boolean value() { return VALUE.matcher(text).matches(); }
    }

    /**
     * The column headers of a financial table, measured on 156 hand-labelled PaddleOCR-VL tables of
     * three public HUT reports: every marked cell was a header (precision 100%), 94% of header rows
     * were found. The first data row is the first row holding a value; its value cells are the value
     * columns and the columns left of them hold the line items. The rows above it are the header,
     * from the top, while each row fills at least half the value columns and, below the first header
     * row or under a caption, leaves the line-item columns empty: a row with item text and no values
     * is a section label and ends the header. One cell over the whole top row is a caption, not a
     * header. The rule abstains, and the table reads by cell address, when the first value is in
     * the first row or below the fifth, when a cell spans both the item and the value columns under
     * the caption, when a header cell reaches down into the data, or when more than three rows
     * qualify. Blank cells are never headers; nor are the line-item cells of the header when the
     * body has section labels, because there a group name and a column name look alike.
     */
    private static Set<Laid> columnHeaders(List<Laid> cells, int rowCount) {
        var rows = new ArrayList<List<Laid>>(rowCount);
        for (int row = 0; row < rowCount; row++) rows.add(new ArrayList<>());
        for (var cell : cells) rows.get(cell.row()).add(cell);
        int first = -1;
        for (int row = 0; row < rowCount && first < 0; row++) {
            if (rows.get(row).stream().anyMatch(Laid::value)) first = row;
        }
        if (first < 1 || first > MAX_HEADER_ROWS + 1) return Set.of();
        var values = new BitSet();
        for (var cell : rows.get(first)) if (cell.value()) values.set(cell.column(), cell.end());
        int items = values.nextSetBit(0);
        var header = new ArrayList<Integer>();
        var carried = new BitSet();
        for (int row = 0; row < first; row++) {
            var filled = rows.get(row).stream().filter(Laid::filled).toList();
            if (filled.stream().anyMatch(cell -> cell.column() < items && cell.end() > items)) {
                if (header.isEmpty() && filled.size() == 1) continue;
                return Set.of();
            }
            var covered = (BitSet) carried.clone();
            for (var cell : filled) covered.set(cell.column(), cell.end());
            covered.and(values);
            if (2 * covered.cardinality() < values.cardinality()) break;
            if ((!header.isEmpty() || row > 0) && filled.stream().anyMatch(cell -> cell.column() < items)) break;
            header.add(row);
            for (var cell : rows.get(row)) if (cell.rowSpan() > 1) carried.set(cell.column(), cell.end());
        }
        if (header.isEmpty() || header.size() > MAX_HEADER_ROWS) return Set.of();
        int last = header.getLast();
        for (int row : header) {
            for (var cell : rows.get(row)) if (cell.row() + cell.rowSpan() - 1 > last) return Set.of();
        }
        boolean sections = false;
        for (int row = last + 1; row < rowCount && !sections; row++) {
            var filled = rows.get(row).stream().filter(Laid::filled).toList();
            sections = filled.stream().anyMatch(cell -> cell.column() < items)
                    && filled.stream().noneMatch(cell -> values.get(cell.column(), cell.end()).cardinality() > 0);
        }
        var marked = new HashSet<Laid>();
        for (int row : header) {
            for (var cell : rows.get(row)) {
                if (cell.filled() && !(sections && cell.column() < items)) marked.add(cell);
            }
        }
        return marked;
    }

    private static int span(Element element, String attribute) {
        try {
            return Math.clamp(Integer.parseInt(element.attr(attribute).strip()), 1, MAX_COLUMNS);
        } catch (NumberFormatException absent) {
            return 1;
        }
    }

    private static ExtractionException failure(ExtractionFailure failure) {
        return DocumentAssembly.failure(failure);
    }
}
