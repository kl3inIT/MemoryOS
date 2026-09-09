package io.memoryos.provider.google;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class GoogleSheetsSourceContentExtractor {
    private final ObjectMapper mapper;
    public GoogleSheetsSourceContentExtractor(ObjectMapper mapper) { this.mapper = mapper; }

    public DocumentContent extract(InputStream content, long size, String filename,
                                   SourceInputDescriptor input) throws ExtractionException {
        JsonNode snapshot = NativeSnapshot.read(mapper, content, size, input, "GOOGLE_SHEETS");
        JsonNode spreadsheet = snapshot.path("content");
        JsonNode sheets = spreadsheet.path("sheets");
        if (!input.providerFileId().equals(spreadsheet.path("spreadsheetId").asString()) || !sheets.isArray()) malformed();
        if (sheets.isEmpty() || sheets.size() > StructuredContent.MAX_TABS) limit();
        StructuredContent output = new StructuredContent(mapper, input);
        output.canonical().set("spreadsheetProperties", spreadsheet.path("properties"));
        if (spreadsheet.has("namedRanges")) output.canonical().set("namedRanges", spreadsheet.get("namedRanges"));
        long represented = 0;
        Set<Integer> sheetIds = new HashSet<>();
        for (JsonNode sheet : sheets) {
            JsonNode properties = sheet.path("properties");
            int sheetId = properties.path("sheetId").asInt(0);
            if (!sheetIds.add(sheetId)) malformed();
            int rows = properties.path("gridProperties").path("rowCount").asInt(-1);
            int columns = properties.path("gridProperties").path("columnCount").asInt(-1);
            if (rows < 1 || columns < 1 || (represented += (long) rows * columns) > StructuredContent.MAX_CELLS) limit();
            String title = properties.path("title").asString("");
            if (title.isBlank()) malformed();
            ObjectNode block = output.block("TABLE");
            block.put("text", title);
            block.set("provenance", properties);
            ObjectNode table = block.putObject("table");
            table.put("rowCount", rows);
            table.put("columnCount", columns);
            table.put("coordinateBase", 0);
            table.put("missingCellValue", "EMPTY");
            table.put("range", "'" + title.replace("'", "''") + "'!A1:" + RestGoogleDriveProvider.columnName(columns) + rows);
            JsonNode merges = sheet.path("merges");
            if (!merges.isMissingNode()) {
                if (!merges.isArray()) malformed();
                for (JsonNode merge : merges) validateMerge(merge, sheetId, rows, columns);
                table.set("merges", merges);
            } else table.putArray("merges");
            ArrayNode cells = table.putArray("cells");
            output.append(title + "\n");
            readPages(sheet.path("pages"), rows, columns, cells, output);
        }
        return output.finish("application/vnd.google-apps.spreadsheet", filename, "google-sheets-native-v1");
    }

    private void readPages(JsonNode pages, int rows, int columns, ArrayNode cells,
                           StructuredContent output) throws ExtractionException {
        if (!pages.isArray() || pages.size() > 256) malformed();
        int expectedRow = 0;
        for (JsonNode page : pages) {
            int start = page.path("startRow").asInt(-1);
            int end = page.path("endRow").asInt(-1);
            if (start != expectedRow || end <= start || end > rows || end - start > 500) malformed();
            expectedRow = end;
            JsonNode grids = page.path("data");
            if (!grids.isArray()) malformed();
            Set<Long> coordinates = new HashSet<>();
            for (JsonNode grid : grids) {
                int firstRow = grid.path("startRow").asInt(0);
                int firstColumn = grid.path("startColumn").asInt(0);
                JsonNode rowData = grid.path("rowData");
                if (rowData.isMissingNode()) continue;
                if (!rowData.isArray() || firstRow < start || firstColumn < 0
                        || (long) firstRow + rowData.size() > end || firstColumn >= columns) malformed();
                for (int r = 0; r < rowData.size(); r++) {
                    JsonNode values = rowData.get(r).path("values");
                    if (values.isMissingNode()) continue;
                    if (!values.isArray() || (long) firstColumn + values.size() > columns) malformed();
                    for (int c = 0; c < values.size(); c++) {
                        int row = firstRow + r;
                        int column = firstColumn + c;
                        if (!coordinates.add((long) row * columns + column)) malformed();
                        JsonNode value = values.get(c);
                        if (!value.isObject()) malformed();
                        output.cell();
                        ObjectNode cell = cells.addObject();
                        cell.put("row", row);
                        cell.put("column", column);
                        String address = RestGoogleDriveProvider.columnName(column + 1) + (row + 1);
                        cell.put("address", address);
                        cell.set("source", value);
                        String display = display(value);
                        cell.put("text", display);
                        if (value.path("userEnteredValue").has("formulaValue")) {
                            cell.put("formula", value.path("userEnteredValue").path("formulaValue").asString());
                        }
                        if (!display.isEmpty()) output.append(address + ": " + display + "\n");
                    }
                }
            }
        }
        if (expectedRow != rows) malformed();
    }

    private static String display(JsonNode cell) {
        if (cell.has("formattedValue")) return cell.path("formattedValue").asString("");
        JsonNode value = cell.has("effectiveValue") ? cell.path("effectiveValue") : cell.path("userEnteredValue");
        for (String field : new String[]{"stringValue", "numberValue", "boolValue", "formulaValue"}) {
            if (value.has(field)) return value.path(field).asString("");
        }
        return value.path("errorValue").path("message").asString(value.path("errorValue").path("type").asString(""));
    }

    private static void validateMerge(JsonNode merge, int sheetId, int rows, int columns) throws ExtractionException {
        int firstRow = merge.path("startRowIndex").asInt(0);
        int firstColumn = merge.path("startColumnIndex").asInt(0);
        int endRow = merge.path("endRowIndex").asInt(-1);
        int endColumn = merge.path("endColumnIndex").asInt(-1);
        if (merge.path("sheetId").asInt(0) != sheetId || firstRow < 0 || firstColumn < 0
                || endRow <= firstRow || endColumn <= firstColumn || endRow > rows || endColumn > columns) malformed();
    }

    private static void malformed() throws ExtractionException { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
    private static void limit() throws ExtractionException { throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT); }
}
