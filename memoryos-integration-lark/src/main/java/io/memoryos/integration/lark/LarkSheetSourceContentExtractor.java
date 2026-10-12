package io.memoryos.integration.lark;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceSnapshotReader;
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
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a Lark Sheet snapshot into one table per sheet, in the shape the Google Sheets reader produces: the sheet's
 * title, then each cell that holds something by its address. Values are what Lark displays; a formula is never
 * evaluated here.
 */
public final class LarkSheetSourceContentExtractor implements SourceSnapshotReader {
    static final String MEDIA_TYPE = "application/vnd.memoryos.lark-sheet+json";

    private final ObjectMapper mapper;

    public LarkSheetSourceContentExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SourceInputFormat format() {
        return SourceInputFormat.LARK_SHEET;
    }

    @Override
    public DocumentContent extract(InputStream content, long size, String filename, SourceInputDescriptor input)
            throws ExtractionException {
        byte[] bytes = StructuredContent.read(content, size, StructuredContent.MAX_BYTES);
        JsonNode snapshot;
        try {
            snapshot = mapper.readTree(bytes);
        } catch (JacksonException exception) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        JsonNode sheets = snapshot == null ? null : snapshot.path("sheets");
        if (snapshot == null || !RestLarkGateway.SHEET_SCHEMA.equals(snapshot.path("schema").asString(""))
                || sheets == null || !sheets.isArray() || input.providerFileId() == null
                || !input.providerFileId().equals(snapshot.path("spreadsheetToken").asString(""))) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        if (sheets.size() > StructuredContent.MAX_TABS) throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);

        var output = new StructuredContent(mapper, input);
        for (int position = 0; position < sheets.size(); position++) {
            JsonNode sheet = sheets.get(position);
            JsonNode values = sheet.path("values");
            if (!values.isArray()) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
            // The gateway keeps no empty row at the end of a sheet, so a sheet without rows holds nothing.
            if (values.isEmpty()) continue;
            String title = sheet.path("title").asString("");
            int index = output.nextIndex();
            var cells = new ArrayList<Cell>();
            int columns = 0;
            if (!title.isBlank()) output.append(title + "\n");
            for (int row = 0; row < values.size(); row++) {
                JsonNode line = values.get(row);
                if (!line.isArray()) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
                columns = Math.max(columns, line.size());
                for (int column = 0; column < line.size(); column++) {
                    output.cell();
                    String text = display(line.get(column));
                    cells.add(Cell.of(row, column, text));
                    if (!text.isEmpty())
                        output.append(RestLarkGateway.column(column + 1) + (row + 1) + ": " + text + "\n");
                }
            }
            output.add(Block.table(index, title, List.of(Location.sheet(position, null, null)),
                    new Table(values.size(), columns, cells), title.isBlank() ? null : title));
        }
        return output.finish(MEDIA_TYPE, LarkDocxSourceContentExtractor.name(filename), "lark-sheet-v1");
    }

    /**
     * What a cell shows. Lark sends a plain value for text and numbers, and a list of segments for a cell with a
     * link, a mention or mixed formatting, each segment carrying its text.
     */
    static String display(JsonNode cell) {
        if (cell == null || cell.isNull() || cell.isMissingNode()) return "";
        if (cell.isArray()) {
            var text = new StringBuilder();
            for (JsonNode segment : cell) text.append(display(segment));
            return text.toString();
        }
        if (cell.isObject()) {
            String text = cell.path("text").asString("");
            return text.isEmpty() ? cell.path("link").asString("") : text;
        }
        return cell.asString("").strip();
    }
}
