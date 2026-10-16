package io.memoryos.integration.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.application.StructuredDocumentChunker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The readers of the Lark snapshots, on blocks and values recorded from the Lark test tenant. */
class LarkExtractionTest {
    private static final String URL = "https://example.larksuite.com/docx/docx-doanh-thu-q3";
    private final ObjectMapper mapper = new ObjectMapper();
    private final LarkDocxSourceContentExtractor documents = new LarkDocxSourceContentExtractor(mapper);
    private final LarkSheetSourceContentExtractor sheets = new LarkSheetSourceContentExtractor(mapper);

    @Test
    void aDocumentKeepsItsHeadingsParagraphsAndTableInOrder() throws Exception {
        byte[] snapshot = docx(recorded("docx-blocks").path("data").path("items"), null);
        var content = documents.extract(stream(snapshot), snapshot.length, "ignored.json", docxInput());

        assertEquals("Báo cáo doanh thu Q3 2026", content.title());
        assertEquals(LarkDocxSourceContentExtractor.MEDIA_TYPE, content.mediaType());
        var canonical = mapper.readTree(content.structuredJson());
        var kinds = canonical.path("blocks").valueStream().map(block -> block.path("kind").asString("")).toList();
        // The page title, the first heading, a paragraph, the table, then "Nhận xét" and its paragraph.
        assertEquals(List.of("HEADING", "HEADING", "PARAGRAPH", "TABLE", "HEADING", "PARAGRAPH"), kinds);
        assertEquals(1, canonical.at("/blocks/0/headingLevel").asInt());
        assertEquals(2, canonical.at("/blocks/4/headingLevel").asInt());
        JsonNode table = canonical.at("/blocks/3/table");
        assertEquals(4, table.path("rowCount").asInt());
        assertEquals(3, table.path("columnCount").asInt());
        assertEquals(12, table.path("cells").size());
        assertEquals("Khu vực", table.at("/cells/0/text").asString());
        assertEquals("Hà Nội", table.at("/cells/3/text").asString());
        assertEquals(1, table.at("/cells/3/row").asInt());
        assertEquals("+6%", table.at("/cells/11/text").asString());
        // The author named no header row, so none is inferred.
        assertFalse(table.at("/cells/0/columnHeader").asBoolean(false));
        assertTrue(content.normalizedText().contains("Doanh thu quý 3 năm 2026 đạt 18,4 tỷ đồng"));
        assertTrue(content.normalizedText().contains("Hà Nội\t7,9\t+15%"));
        assertTrue(content.normalizedText().contains("Đà Nẵng chưa đạt kế hoạch"));
        // A cell's paragraph is part of the table and nowhere else.
        assertEquals(1, content.normalizedText().split("So với Q3 2025", -1).length - 1);
        var chunks = new StructuredDocumentChunker(mapper).chunk(content.title(), content.structuredJson());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("TP. Hồ Chí Minh")));
    }

    @Test
    void aDocumentReadsListsAHeaderRowAndAnEmbeddedSheet() throws Exception {
        String blocks = """
                [{"block_id":"page","block_type":1,"children":["h","b1","o1","t","s","img","nested"],
                  "page":{"elements":[{"text_run":{"content":"Quy trình"}}]}},
                 {"block_id":"h","block_type":5,"heading3":{"elements":[{"text_run":{"content":"Các bước"}}]}},
                 {"block_id":"b1","block_type":12,"children":["b2"],
                  "bullet":{"elements":[{"text_run":{"content":"Gửi yêu cầu "}},{"mention_doc":{"title":"Mẫu đơn"}}]}},
                 {"block_id":"b2","block_type":12,"bullet":{"elements":[{"text_run":{"content":"Chờ duyệt"}}]}},
                 {"block_id":"o1","block_type":13,"ordered":{"elements":[{"text_run":{"content":"Nhận kết quả"}}]}},
                 {"block_id":"t","block_type":31,"children":["c1","c2","c3","c4"],
                  "table":{"cells":["c1","c2","c3","c4"],"property":{"row_size":2,"column_size":2,"header_row":true}}},
                 {"block_id":"c1","block_type":32,"children":["p1"]},
                 {"block_id":"c2","block_type":32,"children":["p2"]},
                 {"block_id":"c3","block_type":32,"children":["p3","p3b"]},
                 {"block_id":"c4","block_type":32,"children":["p4"]},
                 {"block_id":"p1","block_type":2,"text":{"elements":[{"text_run":{"content":"Bước"}}]}},
                 {"block_id":"p2","block_type":2,"text":{"elements":[{"text_run":{"content":"Thời hạn"}}]}},
                 {"block_id":"p3","block_type":2,"text":{"elements":[{"text_run":{"content":"Duyệt"}}]}},
                 {"block_id":"p3b","block_type":2,"text":{"elements":[{"text_run":{"content":"cấp 1"}}]}},
                 {"block_id":"p4","block_type":2,"text":{"elements":[{"text_run":{"content":"2 ngày"}}]}},
                 {"block_id":"s","block_type":30,"sheet":{"token":"sheet-x_tab1"}},
                 {"block_id":"img","block_type":27,"image":{"token":"img-token"}},
                 {"block_id":"nested","block_type":19,"children":["inside"],"callout":{}},
                 {"block_id":"inside","block_type":2,"text":{"elements":[{"text_run":{"content":"Lưu ý quan trọng"}}]}},
                 {"block_id":"orphan","block_type":2,"text":{"elements":[{"text_run":{"content":"Không thuộc cây"}}]}}]""";
        byte[] snapshot = docx(mapper.readTree(blocks),
                mapper.readTree("{\"sheet-x_tab1\":{\"values\":[[\"Mục\",\"Giá\"],[\"A\",100]],\"truncated\":false}}"));
        var content = documents.extract(stream(snapshot), snapshot.length, "ignored.json", docxInput());

        var canonical = mapper.readTree(content.structuredJson());
        var kinds = canonical.path("blocks").valueStream().map(block -> block.path("kind").asString("")).toList();
        assertEquals(List.of("HEADING", "HEADING", "LIST_ITEM", "LIST_ITEM", "LIST_ITEM", "TABLE", "TABLE", "PARAGRAPH"),
                kinds);
        assertEquals(3, canonical.at("/blocks/1/headingLevel").asInt());
        assertEquals("Gửi yêu cầu Mẫu đơn", canonical.at("/blocks/2/text").asString());
        assertEquals("Chờ duyệt", canonical.at("/blocks/3/text").asString());
        JsonNode table = canonical.at("/blocks/5/table");
        assertTrue(table.at("/cells/0/columnHeader").asBoolean());
        assertFalse(table.at("/cells/2/columnHeader").asBoolean(false));
        assertEquals("Duyệt cấp 1", table.at("/cells/2/text").asString());
        assertEquals("100", canonical.at("/blocks/6/table/cells/3/text").asString());
        assertEquals("Lưu ý quan trọng", canonical.at("/blocks/7/text").asString());
        // A block the tree does not reach is not part of the document; an image holds no text.
        assertFalse(content.normalizedText().contains("Không thuộc cây"));
        assertFalse(content.normalizedText().contains("img-token"));
        var chunks = new StructuredDocumentChunker(mapper).chunk(content.title(), content.structuredJson());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("Bước: Duyệt cấp 1")),
                "table rows are labelled by the author's header row");
    }

    @Test
    void aDocumentWithoutContentStillCarriesItsTitle() throws Exception {
        byte[] snapshot = docx(mapper.readTree("""
                [{"block_id":"page","block_type":1,"page":{"elements":[{"text_run":{"content":"Trang trống"}}]}}]"""),
                null);
        var content = documents.extract(stream(snapshot), snapshot.length, "ignored.json", docxInput());
        assertEquals("Trang trống", content.title());
        assertTrue(content.normalizedText().contains("Trang trống"));
    }

    @Test
    void aBlockThatNamesItselfOrGoesTooDeepEndsTheWalk() throws Exception {
        byte[] snapshot = docx(mapper.readTree("""
                [{"block_id":"page","block_type":1,"children":["a"],"page":{"elements":[]}},
                 {"block_id":"a","block_type":2,"children":["a","page"],
                  "text":{"elements":[{"text_run":{"content":"Một lần"}}]}}]"""), null);
        var content = documents.extract(stream(snapshot), snapshot.length, "tep.json", docxInput());
        assertEquals("tep", content.title());
        assertEquals(1, content.normalizedText().split("Một lần", -1).length - 1);
    }

    @Test
    void aSheetKeepsEachCellByItsAddress() throws Exception {
        JsonNode values = recorded("sheet-values").path("data").path("valueRange").path("values");
        byte[] snapshot = sheet("""
                [{"sheetId":"9096cf","title":"Bảng giá","truncated":false,"values":%s},
                 {"sheetId":"empty","title":"Trống","truncated":false,"values":[]}]"""
                .formatted(mapper.writeValueAsString(firstRows(values, 6, 4))));
        var content = sheets.extract(stream(snapshot), snapshot.length, "Bảng giá dịch vụ 2026.json", sheetInput());

        assertEquals("Bảng giá dịch vụ 2026", content.title());
        assertEquals(LarkSheetSourceContentExtractor.MEDIA_TYPE, content.mediaType());
        var canonical = mapper.readTree(content.structuredJson());
        // A sheet without a row holds nothing and adds no block.
        assertEquals(1, canonical.path("blocks").size());
        JsonNode block = canonical.at("/blocks/0");
        assertEquals("TABLE", block.path("kind").asString());
        assertEquals("Bảng giá", block.path("sheetName").asString());
        assertEquals(6, block.at("/table/rowCount").asInt());
        assertEquals(4, block.at("/table/columnCount").asInt());
        assertEquals(24, block.at("/table/cells").size());
        assertTrue(content.normalizedText().startsWith("Bảng giá"));
        assertTrue(content.normalizedText().contains("A1: Dịch vụ"));
        assertTrue(content.normalizedText().contains("C2: 6500000"));
        assertTrue(content.normalizedText().contains("D6: Báo giá theo yêu cầu"));
    }

    @Test
    void aCellOfSegmentsShowsItsText() throws Exception {
        assertEquals("Xem báo cáo tại đây", LarkSheetSourceContentExtractor.display(mapper.readTree("""
                [{"text":"Xem ","type":"text"},{"text":"báo cáo","link":"https://example.com","type":"url"},
                 {"text":" tại đây","type":"text"}]""")));
        assertEquals("https://example.com", LarkSheetSourceContentExtractor.display(
                mapper.readTree("{\"link\":\"https://example.com\",\"type\":\"url\"}")));
        assertEquals("", LarkSheetSourceContentExtractor.display(mapper.nullNode()));
        assertEquals("true", LarkSheetSourceContentExtractor.display(mapper.readTree("true")));
    }

    @Test
    void snapshotsThatDoNotMatchTheItemBeingReadAreRefused() throws Exception {
        byte[] other = docx(recorded("docx-blocks").path("data").path("items"), null);
        var wrongItem = new SourceInputDescriptor(SourceInputFormat.LARK_DOCX, "docx-another", "1", URL);
        assertThrows(ExtractionException.class, () -> documents.extract(stream(other), other.length, "x", wrongItem));
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
        assertThrows(ExtractionException.class,
                () -> documents.extract(stream(notJson), notJson.length, "x", docxInput()));
        byte[] noPage = docx(mapper.readTree("[{\"block_id\":\"a\",\"block_type\":2}]"), null);
        assertThrows(ExtractionException.class,
                () -> documents.extract(stream(noPage), noPage.length, "x", docxInput()));
        // A Docx snapshot is not a Sheet snapshot.
        assertThrows(ExtractionException.class, () -> sheets.extract(stream(other), other.length, "x", sheetInput()));
        byte[] wrongSheet = sheet("[]");
        assertThrows(ExtractionException.class, () -> sheets.extract(stream(wrongSheet), wrongSheet.length, "x",
                new SourceInputDescriptor(SourceInputFormat.LARK_SHEET, "sheet-another", "1", URL)));
    }

    private static SourceInputDescriptor docxInput() {
        return new SourceInputDescriptor(SourceInputFormat.LARK_DOCX, "docx-doanh-thu-q3", "1791739198", URL);
    }

    private static SourceInputDescriptor sheetInput() {
        return new SourceInputDescriptor(SourceInputFormat.LARK_SHEET, "sheet-bang-gia", "1791739198", URL);
    }

    /** The snapshot the gateway writes for a document. */
    private byte[] docx(JsonNode blocks, JsonNode embedded) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schema", RestLarkGateway.DOCX_SCHEMA);
        snapshot.put("documentId", "docx-doanh-thu-q3");
        snapshot.set("blocks", blocks);
        snapshot.set("sheets", embedded == null ? mapper.createObjectNode() : embedded);
        return mapper.writeValueAsBytes(snapshot);
    }

    private byte[] sheet(String sheets) {
        return ("{\"schema\":\"" + RestLarkGateway.SHEET_SCHEMA + "\",\"spreadsheetToken\":\"sheet-bang-gia\",\"sheets\":"
                + sheets + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** The cells that hold something, as the gateway keeps them once it has dropped the empty grid. */
    private JsonNode firstRows(JsonNode values, int rows, int columns) {
        var kept = mapper.createArrayNode();
        for (int row = 0; row < rows; row++) {
            var line = kept.addArray();
            for (int column = 0; column < columns; column++) line.add(values.get(row).get(column));
        }
        return kept;
    }

    private JsonNode recorded(String name) {
        try (var stream = LarkExtractionTest.class.getResourceAsStream("/lark/" + name + ".json")) {
            if (stream == null) throw new IllegalArgumentException("no fixture " + name);
            return mapper.readTree(stream.readAllBytes());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
