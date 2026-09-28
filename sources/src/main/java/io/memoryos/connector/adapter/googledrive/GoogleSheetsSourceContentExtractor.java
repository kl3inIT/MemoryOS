package io.memoryos.connector.adapter.googledrive;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractedDocument.Table;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.document.StructuredContent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class GoogleSheetsSourceContentExtractor {
    private final ObjectMapper mapper;
    public GoogleSheetsSourceContentExtractor(ObjectMapper mapper) { this.mapper = mapper; }

    public DocumentContent extract(InputStream content, long size, String filename,
                                   SourceInputDescriptor input) throws ExtractionException {
        JsonNode snapshot = NativeSnapshot.read(mapper, content, size, input, "GOOGLE_SHEETS");
        JsonNode spreadsheet = snapshot.path("content");
        JsonNode sheets = spreadsheet.path("sheets");
        if (!spreadsheet.path("spreadsheetId").asString().equals(input.providerFileId()) || !sheets.isArray()) malformed();
        if (sheets.isEmpty() || sheets.size() > StructuredContent.MAX_TABS) limit();
        StructuredContent output = new StructuredContent(mapper, input);
        long represented = 0;
        Set<Integer> sheetIds = new HashSet<>();
        for (int position = 0; position < sheets.size(); position++) {
            JsonNode sheet = sheets.get(position);
            JsonNode properties = sheet.path("properties");
            int sheetId = properties.path("sheetId").asInt(0);
            if (!sheetIds.add(sheetId)) malformed();
            int rows = properties.path("gridProperties").path("rowCount").asInt(-1);
            int columns = properties.path("gridProperties").path("columnCount").asInt(-1);
            if (rows < 1 || columns < 1 || (represented += (long) rows * columns) > StructuredContent.MAX_CELLS) limit();
            String title = properties.path("title").asString("");
            if (title.isBlank()) malformed();
            int index = output.nextIndex();
            JsonNode merges = sheet.path("merges");
            if (!merges.isMissingNode()) {
                if (!merges.isArray()) malformed();
                for (JsonNode merge : merges) validateMerge(merge, sheetId, rows, columns);
            }
            var cells = new ArrayList<Cell>();
            output.append(title + "\n");
            readPages(sheet.path("pages"), rows, columns, cells, output);
            // Google Sheets locations never named the sheet; naming it would change how Chat and Search place a citation.
            output.add(Block.table(index, title, List.of(Location.sheet(properties.path("index").asInt(position), null, null)),
                    new Table(rows, columns, cells), title));
        }
        return output.finish("application/vnd.google-apps.spreadsheet", filename, "google-sheets-native-v1");
    }

    private void readPages(JsonNode pages, int rows, int columns, List<Cell> cells,
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
                        String address = RestGoogleDriveProvider.columnName(column + 1) + (row + 1);
                        String display = display(value);
                        cells.add(Cell.of(row, column, display));
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
