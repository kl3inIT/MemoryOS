package io.memoryos.ingestion.extraction;

import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.BoundingBox;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.CoordOrigin;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Line;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractedDocument.Table;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.document.StructuredContent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
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
 * bottom-left origin, the space the viewer's page {@code view} is in.
 *
 * <p>A page turned by {@code /Rotate} is rendered as it is displayed (PDFium applies the rotation),
 * so its boxes are scaled to points of the displayed page and kept from its top left, with the
 * displayed size as their frame. An image input has no page in points: its boxes stay in the pixels
 * they were answered in, from the top left of the picture as the service decoded it (OpenCV, EXIF
 * orientation applied), with the picture's size as their frame.
 *
 * <p>A framed box holds only for the page or picture as sent. The service's document preprocessor
 * turns or unwarps it before the layout is read and answers in the result, so the answer has to say
 * the preprocessor did not run ({@code model_settings.use_doc_preprocessor} false, which the
 * request's two flags ask for). An answer that says it ran keeps no framed box. One that does not
 * say keeps a quarter-turned page's boxes alone, because there the answer's proportions tell the
 * displayed page from the stored one and from a page turned back; a half turn and a picture have no
 * such check.
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
    /**
     * How far the two scales of a rendering may differ and still be one page: PDFium rounds each side
     * up to a pixel, a fraction of a percent at twice the page's points.
     */
    private static final double SAME_PROPORTIONS = 0.01;
    /** The blocks a located line can belong to: text a viewer marks, not a table's cells or a picture. */
    private static final Set<Kind> LINED = Set.of(Kind.PARAGRAPH, Kind.HEADING, Kind.LIST_ITEM);
    /** The most lines kept on one page; a dense A4 statement has about a hundred. */
    static final int MAX_PAGE_LINES = 500;

    private PaddleOcrVlDocument() {}

    /**
     * @param pages the PDF's page frames, one per result; empty for an image input, where each
     *        result is one frame (a multi-page TIFF has several) with its own page number
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
            // A PDF page is not read without the size of its rendering. A picture's text needs none: an
            // answer without it keeps the picture's page alone.
            if (!sized(page) && !pages.isEmpty()) throw failure(ExtractionFailure.MALFORMED);
            var placement = placement(page, pages, index);
            for (int at = 0; at < items.size(); at++) {
                JsonNode item = items.get(at);
                String label = item.path("block_label").asString("");
                boolean tableTitle = "figure_title".equals(label) && namesTheTableBelow(items, at, titles);
                if (TITLES.contains(label)) titles.add(words(item.path("block_content").asString("")));
                if (FURNITURE.contains(label)) continue;
                if (blocks.size() >= StructuredContent.MAX_BLOCKS) throw failure(ExtractionFailure.WRITE_LIMIT);
                String content = item.path("block_content").asString("").strip();
                double[] pixels = pixels(item.path("block_bbox"));
                BoundingBox box = pixels == null || placement == null ? null
                        : placement.frame() != null ? box(pixels, placement.frame(), placement.scaleX(), placement.scaleY())
                        : framed(pixels, placement.scaleX(), placement.scaleY(), placement.shownWidth(),
                                placement.shownHeight());
                if (box != null) {
                    box = outlined(box, item.path("block_polygon_points"), placement.frame(), placement.scaleX(),
                            placement.scaleY());
                }
                var locations = List.of(Location.page(index + 1, box));
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
     * The text lines of a spotting answer ({@code prunedResult.spotting_res}: {@code rec_polys}, a quadrilateral of
     * four {@code [x, y]} pixels for each line, and {@code rec_texts}), each kept in the space of the boxes of its
     * page and given to the smallest text block of that page whose outline, or box, holds the line's centre. A page
     * whose boxes are not kept keeps no line, and a line in no text block (a running header, a page number, a
     * table's cell, a picture) is left out. So is a line without text, longer than {@link Line#MAX_TEXT}, with a
     * corner that is not a number or lies more than a pixel outside the page, or without area; a page keeps at
     * most {@link #MAX_PAGE_LINES}. Nothing here fails the document: the lines only narrow what a viewer marks
     * inside the blocks already read.
     *
     * @param results the spotting answer, one result per page as for {@link #blocks}
     * @param blocks the document's blocks, read from the layout answer of the same input
     */
    static List<Line> lines(JsonNode results, List<PdfLayout.Frame> pages, List<Block> blocks) {
        if (!results.isArray() || !pages.isEmpty() && results.size() != pages.size()) return List.of();
        var lines = new ArrayList<Line>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode page = results.get(index).path("prunedResult");
            var placement = placement(page, pages, index);
            JsonNode spotted = page.path("spotting_res");
            JsonNode quadrilaterals = spotted.path("rec_polys");
            JsonNode texts = spotted.path("rec_texts");
            if (placement == null || !quadrilaterals.isArray() || !texts.isArray()) continue;
            int pageNo = index + 1;
            var owners = blocks.stream()
                    .filter(block -> LINED.contains(block.kind()) && block.locations().size() == 1
                            && Integer.valueOf(pageNo).equals(block.locations().getFirst().pageNo())
                            && block.locations().getFirst().bbox() != null)
                    .sorted(Comparator.comparingDouble(block -> area(block.locations().getFirst().bbox())))
                    .toList();
            int kept = 0;
            for (int at = 0; at < Math.min(quadrilaterals.size(), texts.size()) && kept < MAX_PAGE_LINES; at++) {
                if (!texts.get(at).isString()) continue;
                String text = texts.get(at).asString().strip();
                if (text.isEmpty() || text.length() > Line.MAX_TEXT) continue;
                List<List<Double>> corners = placement.quadrilateral(quadrilaterals.get(at));
                if (corners == null) continue;
                double x = corners.stream().mapToDouble(List::getFirst).average().orElseThrow();
                double y = corners.stream().mapToDouble(List::getLast).average().orElseThrow();
                Block owner = owners.stream().filter(block -> holds(block.locations().getFirst().bbox(), x, y))
                        .findFirst().orElse(null);
                if (owner == null) continue;
                lines.add(new Line(owner.index(), pageNo, text, placement.bounds(corners)));
                kept++;
            }
        }
        return List.copyOf(lines);
    }

    private static boolean sized(JsonNode page) {
        JsonNode width = page.path("width");
        JsonNode height = page.path("height");
        return width.isNumber() && height.isNumber() && width.asDouble() > 0 && height.asDouble() > 0;
    }

    /**
     * Where the boxes of one answered page are kept, or null when they are not: the CropBox's user space for an
     * upright PDF page; the page as displayed for a turned one when the answer's proportions or its preprocessor
     * flag show it was read as displayed; the picture as decoded for an image read as sent.
     */
    private static @Nullable Placement placement(JsonNode page, List<PdfLayout.Frame> pages, int index) {
        if (!sized(page)) return null;
        double width = page.path("width").asDouble();
        double height = page.path("height").asDouble();
        JsonNode preprocessor = page.path("model_settings").path("use_doc_preprocessor");
        boolean asSent = preprocessor.isBoolean() && !preprocessor.asBoolean();
        boolean corrected = preprocessor.isBoolean() && preprocessor.asBoolean();
        if (pages.isEmpty()) return asSent ? new Placement(null, 1, 1, width, height, width, height) : null;
        var stored = pages.get(index);
        if (stored.rotation() == 0) {
            return new Placement(stored, stored.width() / width, stored.height() / height, 0, 0, width, height);
        }
        if (corrected) return null;
        boolean quarter = stored.rotation() != 180;
        double displayedWidth = quarter ? stored.height() : stored.width();
        double displayedHeight = quarter ? stored.width() : stored.height();
        double x = displayedWidth / width;
        double y = displayedHeight / height;
        if ((quarter || asSent) && Math.abs(x / y - 1) <= SAME_PROPORTIONS) {
            return new Placement(null, x, y, displayedWidth, displayedHeight, width, height);
        }
        return null;
    }

    /**
     * One page's mapping from the pixels the service read ({@code width} by {@code height}) to the space its boxes
     * are kept in: user space from {@code frame}'s corner, or, without a frame, a frame of the shown size from its
     * top left.
     */
    private record Placement(PdfLayout.@Nullable Frame frame, double scaleX, double scaleY, double shownWidth,
            double shownHeight, double width, double height) {
        /** Four {@code [x, y]} pixels in this page's space, or null when they are not a quadrilateral on the page. */
        @Nullable List<List<Double>> quadrilateral(JsonNode points) {
            if (!points.isArray() || points.size() != 4) return null;
            var corners = new ArrayList<List<Double>>(4);
            for (JsonNode point : points) {
                if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                    return null;
                }
                double px = point.get(0).asDouble();
                double py = point.get(1).asDouble();
                if (!Double.isFinite(px) || !Double.isFinite(py) || px < -1 || py < -1 || px > width + 1
                        || py > height + 1) {
                    return null;
                }
                px = Math.clamp(px, 0, width);
                py = Math.clamp(py, 0, height);
                double x = frame != null ? frame.x0() + px * scaleX : px * scaleX;
                double y = frame != null ? frame.y0() + frame.height() - py * scaleY : py * scaleY;
                corners.add(List.of(round(x), round(y)));
            }
            return Math.abs(area(corners)) > scaleX * scaleY ? List.copyOf(corners) : null;
        }

        /** The box around a quadrilateral of this page, in this page's space, with the quadrilateral as outline. */
        BoundingBox bounds(List<List<Double>> corners) {
            double minX = corners.stream().mapToDouble(List::getFirst).min().orElseThrow();
            double maxX = corners.stream().mapToDouble(List::getFirst).max().orElseThrow();
            double minY = corners.stream().mapToDouble(List::getLast).min().orElseThrow();
            double maxY = corners.stream().mapToDouble(List::getLast).max().orElseThrow();
            if (frame != null) {
                return new BoundingBox(minX, maxY, maxX, minY, CoordOrigin.BOTTOMLEFT, null, null, corners);
            }
            return new BoundingBox(minX, minY, maxX, maxY, CoordOrigin.TOPLEFT, shownWidth, shownHeight, corners);
        }
    }

    /** Whether a block's outline, or its box when it has none, holds the point, in the box's own space. */
    private static boolean holds(BoundingBox box, double x, double y) {
        var outline = box.polygon();
        if (outline == null) {
            return x >= Math.min(box.l(), box.r()) && x <= Math.max(box.l(), box.r())
                    && y >= Math.min(box.t(), box.b()) && y <= Math.max(box.t(), box.b());
        }
        boolean inside = false;
        for (int i = 0, previous = outline.size() - 1; i < outline.size(); previous = i++) {
            double x1 = outline.get(i).get(0);
            double y1 = outline.get(i).get(1);
            double x2 = outline.get(previous).get(0);
            double y2 = outline.get(previous).get(1);
            if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) inside = !inside;
        }
        return inside;
    }

    /** The area of a box, in its own unit squared. */
    private static double area(BoundingBox box) {
        return Math.abs(box.r() - box.l()) * Math.abs(box.b() - box.t());
    }

    /**
     * {@code [x1, y1, x2, y2]} in pixels from the top left of what the service read, as an array or as
     * its string form; anything else is no box.
     */
    private static double @Nullable [] pixels(JsonNode bbox) {
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
        return values;
    }

    /** A pixel box in user space: x from the CropBox's left edge, y up from its bottom edge. */
    private static BoundingBox box(double[] pixels, PdfLayout.Frame frame, double scaleX, double scaleY) {
        double top = frame.y0() + frame.height();
        return new BoundingBox(frame.x0() + pixels[0] * scaleX, top - pixels[1] * scaleY,
                frame.x0() + pixels[2] * scaleX, top - pixels[3] * scaleY, CoordOrigin.BOTTOMLEFT);
    }

    /**
     * A pixel box in the frame it was read in, scaled to the frame's unit and held to its edges. A box
     * with no area, or more than a pixel outside what the service read, is no box: nothing says where
     * on the frame it is.
     */
    private static @Nullable BoundingBox framed(double[] pixels, double scaleX, double scaleY, double width,
            double height) {
        double left = Math.min(pixels[0], pixels[2]) * scaleX;
        double right = Math.max(pixels[0], pixels[2]) * scaleX;
        double top = Math.min(pixels[1], pixels[3]) * scaleY;
        double bottom = Math.max(pixels[1], pixels[3]) * scaleY;
        if (left < -scaleX || top < -scaleY || right > width + scaleX || bottom > height + scaleY) return null;
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(width, right);
        bottom = Math.min(height, bottom);
        if (right <= left || bottom <= top) return null;
        return new BoundingBox(left, top, right, bottom, CoordOrigin.TOPLEFT, width, height);
    }

    /**
     * The box with the block's outline, when the answer gave one that can be drawn: {@code
     * block_polygon_points}, {@code [x, y]} pixels in the space of {@code block_bbox}, mapped exactly
     * as the box (user space from the CropBox's corner, or the frame), held to the box. An outline
     * with too few or too many points, a point that is not a number or lies more than a pixel outside
     * the box, no area, or that is the box's own four corners leaves the box as it is.
     */
    private static BoundingBox outlined(BoundingBox box, JsonNode points, PdfLayout.@Nullable Frame frame,
            double scaleX, double scaleY) {
        if (!points.isArray() || points.size() < BoundingBox.MIN_POLYGON_POINTS
                || points.size() > BoundingBox.MAX_POLYGON_POINTS) return box;
        double minX = Math.min(box.l(), box.r());
        double maxX = Math.max(box.l(), box.r());
        double minY = Math.min(box.t(), box.b());
        double maxY = Math.max(box.t(), box.b());
        var outline = new ArrayList<List<Double>>(points.size());
        boolean corners = points.size() == 4;
        for (JsonNode point : points) {
            if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) return box;
            double px = point.get(0).asDouble();
            double py = point.get(1).asDouble();
            if (!Double.isFinite(px) || !Double.isFinite(py)) return box;
            double x = frame != null ? frame.x0() + px * scaleX : px * scaleX;
            double y = frame != null ? frame.y0() + frame.height() - py * scaleY : py * scaleY;
            if (x < minX - scaleX || x > maxX + scaleX || y < minY - scaleY || y > maxY + scaleY) return box;
            x = Math.clamp(x, minX, maxX);
            y = Math.clamp(y, minY, maxY);
            boolean onCorner = (Math.abs(x - minX) <= scaleX || Math.abs(x - maxX) <= scaleX)
                    && (Math.abs(y - minY) <= scaleY || Math.abs(y - maxY) <= scaleY);
            corners &= onCorner;
            outline.add(List.of(round(x), round(y)));
        }
        if (corners || !(Math.abs(area(outline)) > scaleX * scaleY)) return box;
        return box.withPolygon(outline);
    }

    /** Twice the signed area of a closed outline (shoelace), in the outline's own unit squared. */
    private static double area(List<List<Double>> outline) {
        double sum = 0;
        for (int i = 0; i < outline.size(); i++) {
            List<Double> from = outline.get(i);
            List<Double> to = outline.get((i + 1) % outline.size());
            sum += from.get(0) * to.get(1) - to.get(0) * from.get(1);
        }
        return sum;
    }

    /** Hundredths of the unit: finer than a pixel of any rendering, and a short provenance string. */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
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
