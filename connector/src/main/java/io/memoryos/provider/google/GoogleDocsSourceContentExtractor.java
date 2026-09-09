package io.memoryos.provider.google;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
        ObjectNode properties = output.canonical().putObject("documentProperties");
        for (String field : List.of("documentId", "title", "revisionId", "documentStyle", "namedStyles", "suggestionsViewMode")) {
            if (document.has(field)) properties.set(field, document.get(field));
        }
        ArrayNode resources = output.canonical().putArray("tabs");
        readTabs(document.path("tabs"), output, resources, new HashSet<>(), 0);
        return output.finish("application/vnd.google-apps.document", filename, "google-docs-native-v1");
    }

    private void readTabs(JsonNode tabs, StructuredContent output, ArrayNode resources,
                          Set<String> ids, int depth) throws ExtractionException {
        if (depth > 100) limit();
        for (JsonNode tab : tabs) {
            output.checkTime();
            JsonNode properties = tab.path("tabProperties");
            String id = properties.path("tabId").asString("");
            if (id.isBlank() || !ids.add(id)) malformed();
            if (ids.size() > StructuredContent.MAX_TABS) limit();
            JsonNode documentTab = tab.path("documentTab");
            if (!documentTab.isObject() || !documentTab.path("body").path("content").isArray()) malformed();
            ObjectNode resource = resources.addObject();
            resource.set("properties", properties);
            for (String field : List.of("lists", "inlineObjects", "positionedObjects", "namedRanges", "documentStyle", "namedStyles")) {
                if (documentTab.has(field)) resource.set(field, documentTab.get(field));
            }
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
                readTabs(children, output, resources, ids, depth + 1);
            }
        }
    }

    private void readElements(JsonNode elements, StructuredContent output, ArrayNode destination,
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
            } else {
                ObjectNode block = block(output, destination, element.has("sectionBreak") ? "SECTION_BREAK" : "STRUCTURAL_ELEMENT");
                provenance(block, element, tabId, section);
                block.set("element", element);
            }
        }
    }

    private void paragraph(JsonNode element, StructuredContent output, ArrayNode destination,
                           String tabId, String section) throws ExtractionException {
        JsonNode paragraph = element.path("paragraph");
        JsonNode elements = paragraph.path("elements");
        if (!elements.isArray()) malformed();
        String style = paragraph.path("paragraphStyle").path("namedStyleType").asString("");
        String kind = paragraph.has("bullet") ? "LIST_ITEM"
                : style.startsWith("HEADING_") || "TITLE".equals(style) || "SUBTITLE".equals(style) ? "HEADING" : "PARAGRAPH";
        ObjectNode block = block(output, destination, kind);
        provenance(block, element, tabId, section);
        block.set("paragraphStyle", paragraph.path("paragraphStyle"));
        block.set("elements", elements);
        if (paragraph.has("positionedObjectIds")) block.set("positionedObjectIds", paragraph.get("positionedObjectIds"));
        if (paragraph.has("bullet")) block.set("bullet", paragraph.get("bullet"));
        if ("HEADING".equals(kind)) block.put("headingStyle", style);
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
        block.put("text", text.toString());
        output.append(text.toString());
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') output.append("\n");
    }

    private void table(JsonNode element, StructuredContent output, ArrayNode destination,
                       String tabId, String section, int depth) throws ExtractionException {
        JsonNode source = element.path("table");
        int rows = source.path("rows").asInt(-1);
        int columns = source.path("columns").asInt(-1);
        JsonNode sourceRows = source.path("tableRows");
        if (rows < 1 || columns < 1 || (long) rows * columns > StructuredContent.MAX_CELLS) limit();
        if (!sourceRows.isArray() || sourceRows.size() != rows) malformed();
        ObjectNode block = block(output, destination, "TABLE");
        provenance(block, element, tabId, section);
        ObjectNode table = block.putObject("table");
        table.put("rowCount", rows);
        table.put("columnCount", columns);
        table.put("coordinateBase", 0);
        if (source.has("tableStyle")) table.set("style", source.get("tableStyle"));
        ArrayNode cells = table.putArray("cells");
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
                ObjectNode cell = cells.addObject();
                cell.put("row", row);
                cell.put("column", column);
                cell.put("rowSpan", rowSpan);
                cell.put("columnSpan", columnSpan);
                cell.set("style", style);
                provenance(cell, sourceCell, tabId, section);
                readElements(sourceCell.path("content"), output, cell.putArray("blocks"), tabId,
                        section + "/table/" + row + "/" + column, depth + 1);
                column += columnSpan;
            }
        }
    }

    private ObjectNode block(StructuredContent output, ArrayNode destination, String kind) throws ExtractionException {
        if (destination == null) return output.block(kind);
        output.checkTime();
        if (destination.size() >= 100_000) limit();
        ObjectNode block = destination.addObject();
        block.put("index", destination.size() - 1);
        block.put("kind", kind);
        return block;
    }

    private static void provenance(ObjectNode block, JsonNode element, String tabId, String section) {
        ObjectNode provenance = block.putObject("provenance");
        provenance.put("tabId", tabId);
        provenance.put("section", section);
        provenance.put("startIndex", element.path("startIndex").asInt(0));
        if (element.has("endIndex")) provenance.set("endIndex", element.get("endIndex"));
    }

    private static void malformed() throws ExtractionException { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
    private static void limit() throws ExtractionException { throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT); }
}
