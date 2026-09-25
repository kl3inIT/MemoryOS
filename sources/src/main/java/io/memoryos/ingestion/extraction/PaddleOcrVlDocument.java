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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
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
 */
final class PaddleOcrVlDocument {
    /** Running headers and footers, page numbers and margin notes, left out of the body as Docling does. */
    private static final Set<String> FURNITURE = Set.of(
            "header", "footer", "number", "header_image", "footer_image", "aside_text");
    private static final Set<String> PICTURES = Set.of("image", "chart", "seal");
    private static final int MAX_COLUMNS = 1_000;

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
            for (JsonNode item : items) {
                String label = item.path("block_label").asString("");
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
                        String text = org.jsoup.Jsoup.parse(content).text().strip();
                        if (!text.isEmpty()) blocks.add(Block.text(position, Kind.PARAGRAPH, text, locations));
                    }
                } else if (content.isEmpty()) {
                    continue;
                } else if ("doc_title".equals(label)) {
                    blocks.add(Block.heading(position, content, 1, locations));
                } else if ("paragraph_title".equals(label)) {
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
     * An HTML table laid out as a browser would: a cell goes to the first column its row has free,
     * so a cell under a {@code rowspan} moves right. Paddle writes only {@code <td>}, so no cell is
     * marked a header; guessing one from its content is what the design rules out.
     */
    static Table table(String html, int[] cellCount) throws ExtractionException {
        Element table = Jsoup.parseBodyFragment(html).selectFirst("table");
        if (table == null) return new Table(0, 0, List.of());
        var rows = table.select("> tr, > thead > tr, > tbody > tr, > tfoot > tr");
        var occupied = new ArrayList<BitSet>(rows.size());
        for (int row = 0; row < rows.size(); row++) occupied.add(new BitSet());
        var cells = new ArrayList<Cell>();
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
                cells.add(new Cell(row, column, rowSpan, columnSpan, false, false, element.text().strip(), List.of()));
                column += columnSpan;
                columns = Math.max(columns, column);
            }
        }
        return new Table(rows.size(), columns, cells);
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
