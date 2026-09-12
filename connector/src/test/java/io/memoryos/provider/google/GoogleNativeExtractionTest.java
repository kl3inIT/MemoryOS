package io.memoryos.provider.google;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.connector.GoogleDriveProvider.FileMetadata;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GoogleNativeExtractionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sheetsPreserveSparseDefaultsZeroFalseFormulaMergesAndAllTabs() throws Exception {
        JsonNode spreadsheet = mapper.readTree("""
                {"spreadsheetId":"sheet1","properties":{"locale":"vi_VN","timeZone":"Asia/Ho_Chi_Minh"},"sheets":[
                  {"properties":{"title":"Revenue","gridProperties":{"rowCount":2,"columnCount":3}},
                   "merges":[{"endRowIndex":1,"startColumnIndex":1,"endColumnIndex":3}],
                   "pages":[{"startRow":0,"endRow":2,"range":"'Revenue'!A1:C2","data":[{"rowData":[
                     {"values":[{"userEnteredValue":{"numberValue":0},"effectiveValue":{"numberValue":0}},
                                 {"userEnteredValue":{"boolValue":false},"effectiveValue":{"boolValue":false}}]},
                     {"values":[{}, {"userEnteredValue":{"formulaValue":"=IMPORTXML(A1,B1)"},
                                      "effectiveValue":{"numberValue":42},"formattedValue":"42.00"}]}]}]}]},
                  {"properties":{"sheetId":7,"title":"Notes","gridProperties":{"rowCount":1,"columnCount":1}},
                   "pages":[{"startRow":0,"endRow":1,"range":"'Notes'!A1:A1","data":[]}]}
                ]}
                """);
        DocumentContent result = extract(spreadsheet, SourceInputFormat.GOOGLE_SHEETS);
        JsonNode canonical = mapper.readTree(result.structuredJson());
        JsonNode cells = canonical.path("blocks").get(0).path("table").path("cells");
        assertEquals("0", cells.get(0).path("text").asString());
        assertEquals("false", cells.get(1).path("text").asString());
        assertEquals("42.00", cells.get(3).path("text").asString());
        assertEquals(1, cells.get(3).path("row").asInt());
        assertEquals(1, cells.get(3).path("column").asInt());
        assertTrue(cells.get(3).path("formula").asString().startsWith("=IMPORTXML"));
        assertEquals(3, canonical.path("blocks").get(0).path("table").path("merges").get(0).path("endColumnIndex").asInt());
        assertEquals("EMPTY", canonical.path("blocks").get(1).path("table").path("missingCellValue").asString());
        assertEquals("vi_VN", canonical.path("spreadsheetProperties").path("locale").asString());
        assertEquals("Asia/Ho_Chi_Minh", canonical.path("spreadsheetProperties").path("timeZone").asString());
        assertEquals("Revenue\nA1: 0\nB1: false\nB2: 42.00\nNotes", result.normalizedText());
        var chunks = new io.memoryos.document.application.StructuredDocumentChunker(mapper).chunk(result.title(), result.structuredJson());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("[B2] 42.00")));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("IMPORTXML")));
    }

    @Test
    void docsPreserveOrderedTabHeadingsListsTablesAndFootnotesOffline() throws Exception {
        JsonNode document = mapper.readTree("""
                {"documentId":"sheet1","revisionId":"docs-revision","tabs":[
                  {"tabProperties":{"tabId":"first","title":"Main"},"documentTab":{
                    "lists":{"list1":{"listProperties":{"nestingLevels":[{"glyphType":"DECIMAL"}]}}},
                    "body":{"content":[
                      {"startIndex":1,"endIndex":9,"paragraph":{"paragraphStyle":{"namedStyleType":"HEADING_1"},"elements":[{"textRun":{"content":"Heading\\n"}}]}},
                      {"startIndex":9,"paragraph":{"bullet":{"listId":"list1","nestingLevel":0},"elements":[{"textRun":{"content":"Entry\\n"}}]}},
                      {"startIndex":15,"table":{"rows":1,"columns":2,"tableRows":[{"tableCells":[
                        {"content":[{"paragraph":{"elements":[{"textRun":{"content":"Left\\n"}}]}}]},
                        {"content":[{"paragraph":{"elements":[{"textRun":{"content":"Right\\n"}}]}}]}
                      ]}]}}
                    ]},
                    "footnotes":{"note1":{"content":[{"paragraph":{"elements":[{"textRun":{"content":"Footnote\\n"}}]}}]}}
                  },"childTabs":[{"tabProperties":{"tabId":"child","title":"Child"},"documentTab":{"body":{"content":[
                    {"paragraph":{"elements":[{"textRun":{"content":"Nested\\n"}}]}}
                  ]}}}]}
                ]}
                """);
        DocumentContent result = extract(document, SourceInputFormat.GOOGLE_DOCS);
        JsonNode canonical = mapper.readTree(result.structuredJson());
        JsonNode blocks = canonical.path("blocks");
        assertEquals("HEADING", blocks.get(0).path("kind").asString());
        assertEquals("LIST_ITEM", blocks.get(1).path("kind").asString());
        assertEquals("list1", blocks.get(1).path("bullet").path("listId").asString());
        assertEquals("TABLE", blocks.get(2).path("kind").asString());
        assertEquals("Right\n", blocks.get(2).path("table").path("cells").get(1).path("blocks").get(0).path("text").asString());
        assertEquals("footnotes/note1", blocks.get(3).path("provenance").path("section").asString());
        assertEquals("child", blocks.get(4).path("provenance").path("tabId").asString());
        assertEquals("Heading\nEntry\nLeft\nRight\nFootnote\nNested", result.normalizedText());
        var chunks = new io.memoryos.document.application.StructuredDocumentChunker(mapper).chunk(result.title(), result.structuredJson());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("[B1] Right")));
        assertTrue(chunks.stream().filter(chunk -> chunk.content().contains("[B1] Right"))
                .allMatch(chunk -> chunk.provenanceJson().contains("first")));
    }

    @Test
    void rejectsIncompleteSheetWindowsAndOversizedRepresentedGrid() {
        ObjectNode sheet = (ObjectNode) mapper.readTree("""
                {"spreadsheetId":"sheet1","sheets":[{"properties":{"title":"Sheet","gridProperties":{"rowCount":501,"columnCount":1}},
                  "pages":[{"startRow":0,"endRow":500,"data":[]}]}]}
                """);
        assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                () -> extract(sheet, SourceInputFormat.GOOGLE_SHEETS)).failure());
        ((ObjectNode) sheet.path("sheets").get(0).path("properties").path("gridProperties")).put("rowCount", 200_001);
        assertEquals(ExtractionFailure.WRITE_LIMIT, assertThrows(ExtractionException.class,
                () -> extract(sheet, SourceInputFormat.GOOGLE_SHEETS)).failure());
    }

    @Test
    void rejectsWrongSnapshotVersionAndNormalizedOutputOverflow() {
        ObjectNode document = mapper.createObjectNode();
        document.put("documentId", "sheet1");
        document.put("revisionId", "revision");
        ObjectNode tab = document.putArray("tabs").addObject();
        tab.putObject("tabProperties").put("tabId", "tab");
        tab.putObject("documentTab").putObject("body").putArray("content").addObject()
                .putObject("paragraph").putArray("elements").addObject().putObject("textRun")
                .put("content", "x".repeat(2_000_001));
        assertEquals(ExtractionFailure.WRITE_LIMIT, assertThrows(ExtractionException.class,
                () -> extract(document, SourceInputFormat.GOOGLE_DOCS)).failure());
        byte[] bytes = mapper.writeValueAsBytes(NativeSnapshot.envelope(mapper, file(), "GOOGLE_DOCS", document));
        var wrong = new SourceInputDescriptor(SourceInputFormat.GOOGLE_DOCS, "sheet1", "other-version", null);
        assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                () -> new GoogleDocsSourceContentExtractor(mapper).extract(new ByteArrayInputStream(bytes), bytes.length, "Notes", wrong)).failure());
    }

    private DocumentContent extract(JsonNode content, SourceInputFormat format) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(NativeSnapshot.envelope(mapper, file(), format.name(), content));
        var input = new SourceInputDescriptor(format, "sheet1", "1", null);
        return format == SourceInputFormat.GOOGLE_SHEETS
                ? new GoogleSheetsSourceContentExtractor(mapper).extract(new ByteArrayInputStream(bytes), bytes.length, "Notes", input)
                : new GoogleDocsSourceContentExtractor(mapper).extract(new ByteArrayInputStream(bytes), bytes.length, "Notes", input);
    }

    private static FileMetadata file() {
        return new FileMetadata("sheet1", "Notes", "application/json", "1", null, null, false, List.of(), null, null);
    }
}
