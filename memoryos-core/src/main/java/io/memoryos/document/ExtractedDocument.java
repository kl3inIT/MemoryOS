package io.memoryos.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The extraction artifact every provider adapter writes and the only shape readers read.
 *
 * <p>Keys belong to MemoryOS, not to a parser: an adapter maps its provider's output onto these
 * records and drops whatever has no place here. Stored `memoryos-extraction-v1` artifacts are
 * upgraded when read, so nothing outside that upgrade knows the older shapes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtractedDocument(
        String schema,
        @Nullable JsonNode source,
        List<Block> blocks,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Page> pages,
        @JsonProperty("page_orientation") @Nullable JsonNode pageOrientation,
        @JsonProperty("financial_checks") @Nullable JsonNode financialChecks,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Line> lines) {
    public static final String SCHEMA = "memoryos-extraction-v2";

    public ExtractedDocument {
        Objects.requireNonNull(schema, "schema");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        pages = pages == null ? List.of() : List.copyOf(pages);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A document whose reader located no text line. */
    public ExtractedDocument(String schema, @Nullable JsonNode source, List<Block> blocks, List<Page> pages,
            @Nullable JsonNode pageOrientation, @Nullable JsonNode financialChecks) {
        this(schema, source, blocks, pages, pageOrientation, financialChecks, List.of());
    }

    public enum Kind { HEADING, PARAGRAPH, LIST_ITEM, TABLE, IMAGE }

    public enum CoordOrigin { TOPLEFT, BOTTOMLEFT }

    /** `index` is the block's position in the reader's output and stays stable when blocks are dropped. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(
            int index,
            Kind kind,
            String text,
            @Nullable Integer headingLevel,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Location> locations,
            @Nullable Table table,
            @Nullable String sheetName,
            @Nullable JsonNode image) {
        public Block {
            Objects.requireNonNull(kind, "kind");
            text = text == null ? "" : text;
            locations = locations == null ? List.of() : List.copyOf(locations);
        }

        public static Block text(int index, Kind kind, String text, List<Location> locations) {
            return new Block(index, kind, text, null, locations, null, null, null);
        }

        public static Block heading(int index, String text, int level, List<Location> locations) {
            return new Block(index, Kind.HEADING, text, level, locations, null, null, null);
        }

        public static Block table(int index, String text, List<Location> locations, Table table,
                @Nullable String sheetName) {
            return new Block(index, Kind.TABLE, text, null, locations, table, sheetName, null);
        }
    }

    /**
     * Where a block came from. A PDF or image location is `page_no` plus an optional `bbox`; a
     * workbook location names its sheet; a Google Docs location names its tab, section and
     * character indices. Chunk provenance, Chat and Search read these keys, so they never change.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(
            @JsonProperty("page_no") @Nullable Integer pageNo,
            @Nullable BoundingBox bbox,
            @Nullable Integer sheetIndex,
            @Nullable String sheetName,
            @Nullable String visibility,
            @Nullable String tabId,
            @Nullable String section,
            @Nullable Integer startIndex,
            @Nullable Integer endIndex) {
        public static Location page(int pageNo, @Nullable BoundingBox bbox) {
            return new Location(pageNo, bbox, null, null, null, null, null, null, null);
        }

        public static Location sheet(int sheetIndex, @Nullable String sheetName, @Nullable String visibility) {
            return new Location(null, null, sheetIndex, sheetName, visibility, null, null, null, null);
        }

        public static Location text(String tabId, String section, int startIndex, @Nullable Integer endIndex) {
            return new Location(null, null, null, null, null, tabId, section, startIndex, endIndex);
        }
    }

    /**
     * A box without a frame is in PDF user space, in points. A box with {@code frame_width} and
     * {@code frame_height} is measured from the top left of a frame of that size as a reader sees it,
     * x right and y down, in the frame's own unit: a pixel of a picture after its EXIF orientation,
     * or a point of a PDF page as displayed, {@code /Rotate} applied. Its origin is {@code TOPLEFT}.
     *
     * <p>{@code polygon}, when present, is the block's own outline as {@code [x, y]} points in the
     * same space and origin as the box, inside it: a tilted or curved block drawn in its own shape
     * rather than as the upright box around it. The box stays the outline's bounds.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BoundingBox(double l, double t, double r, double b,
            @JsonProperty("coord_origin") CoordOrigin coordOrigin,
            @JsonProperty("frame_width") @Nullable Double frameWidth,
            @JsonProperty("frame_height") @Nullable Double frameHeight,
            @Nullable List<List<Double>> polygon) {
        /** The fewest and the most points an outline has. */
        public static final int MIN_POLYGON_POINTS = 3;
        public static final int MAX_POLYGON_POINTS = 64;

        public BoundingBox {
            Objects.requireNonNull(coordOrigin, "coordOrigin");
            if ((frameWidth == null) != (frameHeight == null)) {
                throw new IllegalArgumentException("a frame has a width and a height");
            }
            if (frameWidth != null && (coordOrigin != CoordOrigin.TOPLEFT || !(frameWidth > 0) || !(frameHeight > 0))) {
                throw new IllegalArgumentException("a framed box is measured from the top left of a frame with a size");
            }
            if (polygon != null) {
                if (polygon.size() < MIN_POLYGON_POINTS || polygon.size() > MAX_POLYGON_POINTS) {
                    throw new IllegalArgumentException("an outline has 3 to 64 points");
                }
                for (List<Double> point : polygon) {
                    if (point == null || point.size() != 2 || point.stream().anyMatch(value -> value == null
                            || !Double.isFinite(value))) {
                        throw new IllegalArgumentException("an outline point is a finite [x, y]");
                    }
                }
                polygon = polygon.stream().map(List::copyOf).toList();
            }
        }

        /** A box in PDF user space. */
        public BoundingBox(double l, double t, double r, double b, CoordOrigin coordOrigin) {
            this(l, t, r, b, coordOrigin, null, null, null);
        }

        /** A box without an outline. */
        public BoundingBox(double l, double t, double r, double b, CoordOrigin coordOrigin,
                @Nullable Double frameWidth, @Nullable Double frameHeight) {
            this(l, t, r, b, coordOrigin, frameWidth, frameHeight, null);
        }

        /** This box with the given outline. */
        public BoundingBox withPolygon(List<List<Double>> outline) {
            return new BoundingBox(l, t, r, b, coordOrigin, frameWidth, frameHeight, outline);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Table(@Nullable Integer rowCount, @Nullable Integer columnCount, List<Cell> cells) {
        public Table {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }

        /** Labelled rendering needs explicit column headers; nothing infers them from the first row. */
        public boolean hasColumnHeaders() {
            return cells.stream().anyMatch(Cell::columnHeader);
        }
    }

    /**
     * One table shape for every provider: zero-based coordinates, spans that default to one, and
     * header flags only where the provider said so. A Google Docs cell holds nested blocks.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(
            int row,
            int column,
            @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = SingleSpan.class) Integer rowSpan,
            @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = SingleSpan.class) Integer columnSpan,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) Boolean columnHeader,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) Boolean rowHeader,
            String text,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Block> blocks) {
        public Cell {
            rowSpan = rowSpan == null ? 1 : rowSpan;
            columnSpan = columnSpan == null ? 1 : columnSpan;
            columnHeader = Boolean.TRUE.equals(columnHeader);
            rowHeader = Boolean.TRUE.equals(rowHeader);
            text = text == null ? "" : text;
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }

        public static Cell of(int row, int column, String text) {
            return new Cell(row, column, 1, 1, false, false, text, List.of());
        }
    }

    public record Page(@JsonProperty("page_no") int pageNo, double width, double height) {}

    /**
     * One text line the OCR located inside a block: {@code block} is the block's {@code index}, {@code bbox} is in
     * the block's own space and origin, and its {@code polygon} is the line's quadrilateral as the OCR answered it,
     * corner by corner. A reader that locates no line writes none; a viewer that finds none marks the block whole.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(int block, @JsonProperty("page_no") int pageNo, String text, BoundingBox bbox) {
        /** The most characters a line keeps; the OCR's longest line on an A4 page is far shorter. */
        public static final int MAX_TEXT = 1_000;

        public Line {
            Objects.requireNonNull(bbox, "bbox");
            if (block < 0 || pageNo < 1 || text == null || text.isBlank() || text.length() > MAX_TEXT
                    || bbox.polygon() == null) {
                throw new IllegalArgumentException("a line has a block, a page, its text and its quadrilateral");
            }
        }
    }

    /** Omits the default span, which a workbook of 200,000 cells would otherwise repeat. */
    static final class SingleSpan {
        @Override
        public boolean equals(Object value) {
            return Integer.valueOf(1).equals(value);
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
