package io.memoryos.connector.adapter.googledrive;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractedDocument.Table;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.ingestion.extraction.StructuredContent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class GoogleDocsSourceContentExtractor {
    private final ObjectMapper mapper;
    public GoogleDocsSourceContentExtractor(ObjectMapper mapper) { this.mapper = mapper; }

    public DocumentContent extract(InputStream content, long size, String filename,
                                   SourceInputDescriptor input) throws ExtractionException {
        JsonNode snapshot = NativeSnapshot.read(mapper, content, size, input, "GOOGLE_DOCS");
        JsonNode document = snapshot.path("content");
        if (!input.providerFileId().equals(document.path("documentId").asString())
                || !document.path("tabs").isArray() || document.path("tabs").isEmpty()) malformed();
        StructuredContent output = new StructuredContent(mapper, input);
        readTabs(document.path("tabs"), output, new HashSet<>(), 0);
        return output.finish("application/vnd.google-apps.document", filename, "google-docs-native-v1");
    }

    private void readTabs(JsonNode tabs, StructuredContent output, Set<String> ids, int depth) throws ExtractionException {
        if (depth > 100) limit();
        for (JsonNode tab : tabs) {
            output.checkTime();
            JsonNode properties = tab.path("tabProperties");
            String id = properties.path("tabId").asString("");
            if (id.isBlank() || !ids.add(id)) malformed();
            if (ids.size() > StructuredContent.MAX_TABS) limit();
            JsonNode documentTab = tab.path("documentTab");
            if (!documentTab.isObject() || !documentTab.path("body").path("content").isArray()) malformed();
            readElements(documentTab.path("body").path("content"), output, null, id, "body", 0);
            for (String section : List.of("headers", "footers", "footnotes")) {
                JsonNode sections = documentTab.path(section);
                if (sections.isMissingNode()) continue;
                if (!sections.isObject()) malformed();
                List<String> keys = new ArrayList<>(sections.propertyNames());
                keys.sort(String::compareTo);
                for (String key : keys) readElements(sections.path(key).path("content"), output, null,
                        id, section + "/" + key, 0);
            }
            JsonNode children = tab.path("childTabs");
            if (!children.isMissingNode()) {
                if (!children.isArray()) malformed();
                readTabs(children, output, ids, depth + 1);
            }
        }
    }

    /** Blocks go to the document, or to a table cell when `destination` is that cell's list. */
    private void readElements(JsonNode elements, StructuredContent output, @Nullable List<Block> destination,
                              String tabId, String section, int depth) throws ExtractionException {
        if (depth > 100) limit();
        if (!elements.isArray()) malformed();
        for (JsonNode element : elements) {
            output.checkTime();
            if (element.has("paragraph")) {
                paragraph(element, output, destination, tabId, section);
            } else if (element.has("table")) {
                table(element, output, destination, tabId, section, depth);
            } else if (element.has("tableOfContents")) {
                readElements(element.path("tableOfContents").path("content"), output, destination,
                        tabId, section + "/tableOfContents", depth + 1);
            }
            // Section breaks and other structural elements carry no text and have no block kind.
        }
    }

    private void paragraph(JsonNode element, StructuredContent output, @Nullable List<Block> destination,
                           String tabId, String section) throws ExtractionException {
        JsonNode paragraph = element.path("paragraph");
        JsonNode elements = paragraph.path("elements");
        if (!elements.isArray()) malformed();
        String style = paragraph.path("paragraphStyle").path("namedStyleType").asString("");
        Kind kind = paragraph.has("bullet") ? Kind.LIST_ITEM
                : style.startsWith("HEADING_") || "TITLE".equals(style) || "SUBTITLE".equals(style) ? Kind.HEADING : Kind.PARAGRAPH;
        int index = index(output, destination);
        StringBuilder text = new StringBuilder();
        for (JsonNode run : elements) {
            String value;
            if (run.has("textRun")) value = run.path("textRun").path("content").asString("");
            else if (run.has("person")) value = run.path("person").path("personProperties").path("name").asString("");
            else if (run.has("richLink")) value = run.path("richLink").path("richLinkProperties").path("title").asString("");
            else if (run.has("dateElement")) value = run.path("dateElement").path("dateElementProperties").path("displayText").asString("");
            else value = "";
            if ((long) text.length() + value.length() > StructuredContent.MAX_TEXT) limit();
            text.append(value);
        }
        // Every heading style reads as a top-level heading, as it always has.
        add(output, destination, Block.text(index, kind, text.toString(), List.of(location(element, tabId, section))));
        output.append(text.toString());
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') output.append("\n");
    }

    private void table(JsonNode element, StructuredContent output, @Nullable List<Block> destination,
                       String tabId, String section, int depth) throws ExtractionException {
        JsonNode source = element.path("table");
        int rows = source.path("rows").asInt(-1);
        int columns = source.path("columns").asInt(-1);
        JsonNode sourceRows = source.path("tableRows");
        if (rows < 1 || columns < 1 || (long) rows * columns > StructuredContent.MAX_CELLS) limit();
        if (!sourceRows.isArray() || sourceRows.size() != rows) malformed();
        int index = index(output, destination);
        var cells = new ArrayList<Cell>();
        boolean[] occupied = new boolean[rows * columns];
        for (int row = 0; row < rows; row++) {
            JsonNode sourceCells = sourceRows.get(row).path("tableCells");
            if (!sourceCells.isArray()) malformed();
            int column = 0;
            for (JsonNode sourceCell : sourceCells) {
                output.cell();
                while (column < columns && occupied[row * columns + column]) column++;
                JsonNode style = sourceCell.path("tableCellStyle");
                int rowSpan = style.path("rowSpan").asInt(1);
                int columnSpan = style.path("columnSpan").asInt(1);
                if (rowSpan < 1 || columnSpan < 1 || (long) row + rowSpan > rows || (long) column + columnSpan > columns) malformed();
                for (int r = row; r < row + rowSpan; r++) for (int c = column; c < column + columnSpan; c++) {
                    if (occupied[r * columns + c]) malformed();
                    occupied[r * columns + c] = true;
                }
                var blocks = new ArrayList<Block>();
                readElements(sourceCell.path("content"), output, blocks, tabId,
                        section + "/table/" + row + "/" + column, depth + 1);
                cells.add(new Cell(row, column, rowSpan, columnSpan, false, false, "", blocks));
                column += columnSpan;
            }
        }
        add(output, destination, Block.table(index, "", List.of(location(element, tabId, section)),
                new Table(rows, columns, cells), null));
    }

    private static int index(StructuredContent output, @Nullable List<Block> destination) throws ExtractionException {
        if (destination == null) return output.nextIndex();
        output.checkTime();
        if (destination.size() >= StructuredContent.MAX_BLOCKS) limit();
        return destination.size();
    }

    private static void add(StructuredContent output, @Nullable List<Block> destination, Block block) {
        if (destination == null) output.add(block);
        else destination.add(block);
    }

    private static Location location(JsonNode element, String tabId, String section) {
        return Location.text(tabId, section, element.path("startIndex").asInt(0),
                element.has("endIndex") ? element.path("endIndex").asInt() : null);
    }

    private static void malformed() throws ExtractionException { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
    private static void limit() throws ExtractionException { throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT); }
}
