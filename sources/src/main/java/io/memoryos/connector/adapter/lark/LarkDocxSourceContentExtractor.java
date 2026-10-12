package io.memoryos.connector.adapter.lark;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Table;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.document.StructuredContent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a Lark Docx snapshot into canonical blocks. Lark stores a document as a tree of blocks, each naming its
 * children; the tree is walked from the page block, so headings, paragraphs, list items and tables keep their order
 * and their shape. A table's cells are blocks holding more blocks; an embedded spreadsheet is the values the
 * snapshot carries for it. A block of a kind that holds no text (an image, a diagram, a board) contributes nothing
 * of its own, and its children are still read.
 */
public final class LarkDocxSourceContentExtractor {
    static final String MEDIA_TYPE = "application/vnd.memoryos.lark-docx+json";
    private static final int MAX_DEPTH = 64;
    private static final int PAGE = 1;
    private static final int TEXT = 2;
    private static final int HEADING_1 = 3;
    private static final int HEADING_9 = 11;
    private static final int BULLET = 12;
    private static final int ORDERED = 13;
    private static final int CODE = 14;
    private static final int QUOTE = 15;
    private static final int TODO = 17;
    private static final int CALLOUT = 19;
    private static final int SHEET = 30;
    private static final int TABLE = 31;
    /** The member that holds a text block's content, by block type. */
    private static final Map<Integer, String> TEXT_MEMBERS = Map.of(PAGE, "page", TEXT, "text", BULLET, "bullet",
            ORDERED, "ordered", CODE, "code", QUOTE, "quote", TODO, "todo", CALLOUT, "callout");

    private final ObjectMapper mapper;

    public LarkDocxSourceContentExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public DocumentContent extract(InputStream content, long size, String filename, SourceInputDescriptor input)
            throws ExtractionException {
        byte[] bytes = StructuredContent.read(content, size, StructuredContent.MAX_BYTES);
        JsonNode snapshot;
        try {
            snapshot = mapper.readTree(bytes);
        } catch (JacksonException exception) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        if (snapshot == null || !RestLarkGateway.DOCX_SCHEMA.equals(snapshot.path("schema").asString(""))
                || !snapshot.path("blocks").isArray() || input.providerFileId() == null
                || !input.providerFileId().equals(snapshot.path("documentId").asString(""))) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        var blocks = new HashMap<String, JsonNode>();
        JsonNode page = null;
        for (JsonNode block : snapshot.path("blocks")) {
            String id = block.path("block_id").asString("");
            if (id.isEmpty()) continue;
            blocks.putIfAbsent(id, block);
            if (page == null && block.path("block_type").asInt(0) == PAGE) page = block;
        }
        if (page == null) throw StructuredContent.failure(ExtractionFailure.MALFORMED);

        var output = new StructuredContent(mapper, input);
        var walk = new Walk(output, blocks, snapshot.path("sheets"));
        walk.visited.add(page.path("block_id").asString(""));
        String title = text(page);
        if (!title.isBlank()) walk.heading(title, 1);
        walk.children(page, 0);
        return output.finish(MEDIA_TYPE, title.isBlank() ? name(filename) : title, "lark-docx-v1");
    }

    /** The name Lark gave the object: a snapshot is stored under that name with {@code .json} after it. */
    static String name(String filename) {
        return filename.endsWith(".json") && filename.length() > 5
                ? filename.substring(0, filename.length() - 5) : filename;
    }

    /** The text of one block: its text runs, the titles of the documents it mentions, its equations. */
    private static String text(JsonNode block) {
        int type = block.path("block_type").asInt(0);
        String member = type >= HEADING_1 && type <= HEADING_9 ? "heading" + (type - HEADING_1 + 1)
                : TEXT_MEMBERS.get(type);
        if (member == null) return "";
        var text = new StringBuilder();
        for (JsonNode element : block.path(member).path("elements")) {
            text.append(element.path("text_run").path("content").asString(""));
            text.append(element.path("mention_doc").path("title").asString(""));
            text.append(element.path("equation").path("content").asString(""));
        }
        return text.toString().strip();
    }

    private static final class Walk {
        private final StructuredContent output;
        private final Map<String, JsonNode> blocks;
        private final JsonNode sheets;
        private final Set<String> visited = new HashSet<>();

        Walk(StructuredContent output, Map<String, JsonNode> blocks, JsonNode sheets) {
            this.output = output;
            this.blocks = blocks;
            this.sheets = sheets;
        }

        void children(JsonNode parent, int depth) throws ExtractionException {
            if (depth > MAX_DEPTH) return;
            for (JsonNode id : parent.path("children")) {
                JsonNode block = blocks.get(id.asString(""));
                // A block is read once, where the tree first names it.
                if (block == null || !visited.add(id.asString(""))) continue;
                output.checkTime();
                block(block, depth);
            }
        }

        private void block(JsonNode block, int depth) throws ExtractionException {
            int type = block.path("block_type").asInt(0);
            if (type == TABLE) {
                table(block);
                return;
            }
            if (type == SHEET) {
                sheet(block.path("sheet").path("token").asString(""));
                return;
            }
            String text = text(block);
            if (type >= HEADING_1 && type <= HEADING_9) heading(text, type - HEADING_1 + 1);
            else if (type == BULLET || type == ORDERED || type == TODO) add(Kind.LIST_ITEM, text);
            else add(Kind.PARAGRAPH, text);
            children(block, depth + 1);
        }

        void heading(String text, int level) throws ExtractionException {
            if (text.isBlank()) return;
            output.add(Block.heading(output.nextIndex(), text, Math.clamp(level, 1, 6), List.of()));
            output.append(text);
            output.append("\n\n");
        }

        private void add(Kind kind, String text) throws ExtractionException {
            if (text.isBlank()) return;
            output.add(kind, text);
            output.append(text);
            output.append("\n");
        }

        /**
         * A native table: Lark lists its cells row by row, each a block holding the cell's own blocks. The first row
         * heads the columns only where the author said so.
         */
        private void table(JsonNode block) throws ExtractionException {
            JsonNode property = block.path("table").path("property");
            int rows = property.path("row_size").asInt(0);
            int columns = property.path("column_size").asInt(0);
            JsonNode ids = block.path("table").path("cells");
            if (rows < 1 || columns < 1 || !ids.isArray() || (long) rows * columns != ids.size()) {
                // Not the shape Lark documents: its cells are read as ordinary blocks instead.
                children(block, 0);
                return;
            }
            boolean headerRow = property.path("header_row").asBoolean(false);
            boolean headerColumn = property.path("header_column").asBoolean(false);
            int index = output.nextIndex();
            var cells = new ArrayList<Cell>(ids.size());
            for (int position = 0; position < ids.size(); position++) {
                output.cell();
                int row = position / columns;
                int column = position % columns;
                String id = ids.get(position).asString("");
                visited.add(id);
                String text = cellText(blocks.get(id), 0);
                cells.add(new Cell(row, column, 1, 1, headerRow && row == 0, headerColumn && column == 0 && !(headerRow && row == 0),
                        text, List.of()));
                output.append(text);
                output.append(column == columns - 1 ? "\n" : "\t");
            }
            output.add(Block.table(index, "", List.of(), new Table(rows, columns, cells), null));
        }

        /** The text of everything a table cell holds, its blocks joined by a space. */
        private String cellText(@Nullable JsonNode cell, int depth) {
            if (cell == null || depth > MAX_DEPTH) return "";
            var parts = new ArrayList<String>();
            for (JsonNode id : cell.path("children")) {
                JsonNode child = blocks.get(id.asString(""));
                if (child == null || !visited.add(id.asString(""))) continue;
                String own = text(child);
                if (!own.isBlank()) parts.add(own);
                String nested = cellText(child, depth + 1);
                if (!nested.isBlank()) parts.add(nested);
            }
            return String.join(" ", parts);
        }

        /** An embedded spreadsheet, from the values the snapshot read for it; one the app could not read is a gap. */
        private void sheet(String token) throws ExtractionException {
            JsonNode values = sheets.path(token).path("values");
            if (!values.isArray() || values.isEmpty()) return;
            int index = output.nextIndex();
            var cells = new ArrayList<Cell>();
            int columns = 0;
            for (int row = 0; row < values.size(); row++) {
                JsonNode line = values.get(row);
                columns = Math.max(columns, line.size());
                for (int column = 0; column < line.size(); column++) {
                    output.cell();
                    String text = LarkSheetSourceContentExtractor.display(line.get(column));
                    cells.add(Cell.of(row, column, text));
                    output.append(text);
                    output.append(column == line.size() - 1 ? "" : "\t");
                }
                output.append("\n");
            }
            output.add(Block.table(index, "", List.of(), new Table(values.size(), columns, cells), null));
        }
    }
}
