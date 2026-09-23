package io.memoryos.document.application;

import io.memoryos.document.DocumentContentException;
import io.memoryos.document.ExtractedDocument;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.BoundingBox;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.CoordOrigin;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractedDocument.Page;
import io.memoryos.document.ExtractedDocument.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a stored extraction artifact as {@link ExtractedDocument}.
 *
 * <p>This is the only code that knows `memoryos-extraction-v1`. That schema had two flavours: the
 * Docling route copied Docling's `prov` items, `TableData.table_cells` offsets and `pages` object;
 * the native readers wrote object provenance and `table.cells`, and SharePoint pages wrote
 * lowercase kinds with `rows`. Upgrading at read time lets a chunk convention change regenerate
 * chunks from stored artifacts without extracting or OCRing the originals again.
 */
final class ExtractionArtifactReader {
    static final String V1 = "memoryos-extraction-v1";
    private static final int MAX_DEPTH = 100;

    private ExtractionArtifactReader() {}

    static ExtractedDocument read(ObjectMapper mapper, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.path("blocks").isArray()) throw invalid();
            return switch (root.path("schema").asString("")) {
                case ExtractedDocument.SCHEMA -> mapper.treeToValue(root, ExtractedDocument.class);
                case V1 -> upgrade(root);
                default -> throw invalid();
            };
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    private static ExtractedDocument upgrade(JsonNode root) {
        return new ExtractedDocument(ExtractedDocument.SCHEMA, optional(root.path("source")),
                blocks(root.path("blocks"), 0), pages(root.path("pages")),
                optional(root.path("page_orientation")), optional(root.path("financial_checks")));
    }

    private static List<Block> blocks(JsonNode source, int depth) {
        if (depth > MAX_DEPTH) throw new DocumentContentException("SEARCH_INDEX_CONTENT_LIMIT", "table exceeds bounds");
        var blocks = new ArrayList<Block>(source.size());
        int position = 0;
        for (JsonNode block : source) {
            // v1 readers took a missing index from the block's position.
            int index = block.path("index").asInt(position++);
            JsonNode provenance = block.path("provenance");
            String sheetName = block.path("sheetName").isString() ? block.path("sheetName").asString()
                    : provenance.path("sheetName").isString() ? provenance.path("sheetName").asString()
                    : googleSheet(provenance) ? provenance.path("title").asString() : null;
            JsonNode level = block.has("headingLevel") ? block.path("headingLevel") : block.path("level");
            blocks.add(new Block(index, kind(block.path("kind").asString("")), block.path("text").asString(""),
                    level.isNumber() ? level.asInt() : null, locations(provenance),
                    table(block.path("table"), block.path("rows"), depth), sheetName, optional(block.path("image"))));
        }
        return blocks;
    }

    /**
     * SharePoint pages wrote lowercase kinds. Google Docs structural elements and section breaks
     * have no text; like any other unknown kind they read as paragraphs, which v1 chunking did.
     */
    private static Kind kind(String kind) {
        return switch (kind) {
            case "HEADING", "heading" -> Kind.HEADING;
            case "LIST_ITEM", "listItem" -> Kind.LIST_ITEM;
            case "TABLE", "table" -> Kind.TABLE;
            case "IMAGE" -> Kind.IMAGE;
            default -> Kind.PARAGRAPH;
        };
    }

    private static List<Location> locations(JsonNode provenance) {
        if (provenance.isObject()) return List.of(location(provenance));
        if (!provenance.isArray()) return List.of();
        var locations = new ArrayList<Location>(provenance.size());
        for (JsonNode item : provenance) {
            if (item.isObject()) locations.add(location(item));
        }
        return locations;
    }

    /** Keeps the contract keys only: Docling `charspan` and Google Sheets grid properties are dropped. */
    private static Location location(JsonNode item) {
        JsonNode page = item.path("page_no");
        Integer pageNo = page.isIntegralNumber() && page.canConvertToInt() && page.asInt() >= 1 ? page.asInt() : null;
        Integer sheetIndex = integer(item.path("sheetIndex"));
        if (sheetIndex == null && googleSheet(item)) sheetIndex = integer(item.path("index"));
        return new Location(pageNo, pageNo == null ? null : bbox(item.path("bbox")), sheetIndex,
                text(item.path("sheetName")), text(item.path("visibility")), text(item.path("tabId")),
                text(item.path("section")), integer(item.path("startIndex")), integer(item.path("endIndex")));
    }

    /** Google Sheets stored the sheet's own `properties` object as provenance. */
    private static boolean googleSheet(JsonNode provenance) {
        return provenance.isObject() && provenance.path("title").isString() && provenance.has("gridProperties");
    }

    private static @Nullable BoundingBox bbox(JsonNode bbox) {
        for (String side : List.of("l", "t", "r", "b")) {
            if (!bbox.path(side).isNumber()) return null;
        }
        return new BoundingBox(bbox.path("l").asDouble(), bbox.path("t").asDouble(), bbox.path("r").asDouble(),
                bbox.path("b").asDouble(),
                "TOPLEFT".equals(bbox.path("coord_origin").asString("")) ? CoordOrigin.TOPLEFT : CoordOrigin.BOTTOMLEFT);
    }

    private static @Nullable Table table(JsonNode table, JsonNode rows, int depth) {
        if (table.path("table_cells").isArray()) return doclingTable(table);
        if (table.path("cells").isArray()) {
            var cells = new ArrayList<Cell>(table.path("cells").size());
            for (JsonNode cell : table.path("cells")) {
                // A missing coordinate stays invalid, as v1 chunking rejected it.
                cells.add(new Cell(cell.path("row").asInt(-1), cell.path("column").asInt(-1),
                        cell.path("rowSpan").asInt(1), cell.path("columnSpan").asInt(1), false, false,
                        cell.path("text").asString(""), blocks(cell.path("blocks"), depth + 1)));
            }
            return new Table(integer(table.path("rowCount")), integer(table.path("columnCount")), cells);
        }
        if (rows.isArray()) {
            var cells = new ArrayList<Cell>();
            for (int row = 0; row < rows.size(); row++) {
                for (int column = 0; column < rows.get(row).size(); column++) {
                    cells.add(Cell.of(row, column, rows.get(row).get(column).asString("")));
                }
            }
            return new Table(rows.size(), null, cells);
        }
        return null;
    }

    private static Table doclingTable(JsonNode table) {
        var cells = new ArrayList<Cell>(table.path("table_cells").size());
        for (JsonNode cell : table.path("table_cells")) {
            // v1 chunking read absent offsets as zero and absent ends as a span of one.
            int row = cell.path("start_row_offset_idx").asInt();
            int column = cell.path("start_col_offset_idx").asInt();
            cells.add(new Cell(row, column, cell.path("end_row_offset_idx").asInt(row + 1) - row,
                    cell.path("end_col_offset_idx").asInt(column + 1) - column,
                    cell.path("column_header").asBoolean(false), cell.path("row_header").asBoolean(false),
                    cell.path("text").asString(""), List.of()));
        }
        return new Table(integer(table.path("num_rows")), integer(table.path("num_cols")), cells);
    }

    private static List<Page> pages(JsonNode pages) {
        if (!pages.isObject() && !pages.isArray()) return List.of();
        var result = new ArrayList<Page>(pages.size());
        for (JsonNode page : pages) {
            JsonNode size = page.path("size");
            if (!page.path("page_no").isIntegralNumber() || !size.path("width").isNumber()
                    || !size.path("height").isNumber()) continue;
            result.add(new Page(page.path("page_no").asInt(), size.path("width").asDouble(), size.path("height").asDouble()));
        }
        result.sort(Comparator.comparingInt(Page::pageNo));
        return result;
    }

    private static @Nullable Integer integer(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? value.asInt() : null;
    }

    private static @Nullable String text(JsonNode value) {
        return value.isString() ? value.asString() : null;
    }

    private static @Nullable JsonNode optional(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? null : value;
    }

    private static DocumentContentException invalid() {
        return new DocumentContentException("SEARCH_INDEX_ARTIFACT_INVALID", "unsupported extraction artifact");
    }
}
