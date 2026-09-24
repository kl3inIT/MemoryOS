package io.memoryos.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.document.application.StructuredDocumentChunker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StructuredDocumentChunkerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredDocumentChunker chunker = new StructuredDocumentChunker(mapper);

    @Test
    void nativeTableIndexesValuesCoordinatesAndSheetWithoutExecutingFormulas() {
        var chunks = chunker.chunk("Báo cáo", """
                {"schema":"memoryos-extraction-v2","blocks":[{"index":0,"kind":"TABLE","text":"Doanh thu",
                  "sheetName":"Doanh thu","locations":[{"sheetIndex":1,"sheetName":"Doanh thu"}],"table":{"cells":[
                  {"row":0,"column":0,"text":"Chỉ tiêu"},
                  {"row":1,"column":0,"text":"Việt Nam"},
                  {"row":1,"column":1,"text":"90,5","formula":"SECRET_FORMULA()"}
                ]}}]}
                """);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(1).content().contains("[A2] Việt Nam"));
        assertTrue(chunks.get(1).content().contains("[B2] 90,5"));
        assertTrue(chunks.get(1).content().contains("Doanh thu"));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("SECRET_FORMULA")));
        assertEquals(1, mapper.readTree(chunks.get(1).provenanceJson()).path("source").path("sheetIndex").asInt());
    }

    @Test
    void nativeDocumentTableReadsNestedCellBlocks() {
        var chunks = chunker.chunk("Tài liệu", """
                {"schema":"memoryos-extraction-v2","blocks":[{"index":0,"kind":"TABLE","text":"","table":{"cells":[
                  {"row":0,"column":0,"text":"","blocks":[{"index":0,"kind":"PARAGRAPH","text":"Nội dung trong ô"}]}
                ]}}]}
                """);
        assertTrue(chunks.getFirst().content().contains("[A1] Nội dung trong ô"));
    }

    @Test
    void preservesVietnameseSectionAndRealPageLocation() {
        var chunks = chunker.chunk("Chính sách công tác", """
                {"schema":"memoryos-extraction-v2","blocks":[
                  {"index":0,"kind":"HEADING","headingLevel":1,"text":"Thanh toán"},
                  {"index":1,"kind":"PARAGRAPH","text":"Nộp hóa đơn trong vòng 07 ngày.",
                   "locations":[{"page_no":3,"bbox":{"l":12.5,"t":20.0,"r":300.0,"b":40.0,"coord_origin":"TOPLEFT"}}]}]}
                """);
        assertEquals(2, chunks.size());
        var passage = chunks.get(1);
        assertTrue(passage.content().contains("Chính sách công tác"));
        assertTrue(passage.content().contains("Thanh toán"));
        assertTrue(passage.content().contains("07 ngày"));
        var location = mapper.readTree(passage.provenanceJson());
        assertEquals(3, location.path("page_no").asInt());
        assertEquals(12.5, location.path("bbox").path("l").asDouble());
        assertEquals("TOPLEFT", location.path("bbox").path("coord_origin").asString());
        assertEquals(1, passage.blockIndex());
        assertEquals(chunks, chunker.chunk("Chính sách công tác", """
                {"schema":"memoryos-extraction-v2","blocks":[
                  {"index":0,"kind":"HEADING","headingLevel":1,"text":"Thanh toán"},
                  {"index":1,"kind":"PARAGRAPH","text":"Nộp hóa đơn trong vòng 07 ngày.",
                   "locations":[{"page_no":3,"bbox":{"l":12.5,"t":20.0,"r":300.0,"b":40.0,"coord_origin":"TOPLEFT"}}]}]}
                """));
    }

    @Test
    void tableKeepsHeadersNumbersAndRowLocation() {
        var chunks = chunker.chunk("Báo cáo KPI", """
                {"schema":"memoryos-extraction-v2","blocks":[{"index":4,"kind":"TABLE","text":"","table":{
                  "cells":[
                    {"text":"Chỉ tiêu","columnHeader":true,"row":0,"column":0},
                    {"text":"Quý II (%)","columnHeader":true,"row":0,"column":1},
                    {"text":"Hoàn thành kế hoạch","rowHeader":true,"row":1,"column":0},
                    {"text":"90,5","row":1,"column":1}
                  ]}}]}
                """);
        assertEquals(1, chunks.size());
        assertTrue(chunks.getFirst().content().contains("Chỉ tiêu: Hoàn thành kế hoạch"));
        assertTrue(chunks.getFirst().content().contains("Quý II (%): 90,5"));
        assertEquals(1, mapper.readTree(chunks.getFirst().provenanceJson()).path("tableRow").asInt());
    }

    @Test
    void tableExpandsMergedColumnHeadersAndRepeatedRowHeaders() {
        var chunks = chunker.chunk("Báo cáo tài chính", """
                {"schema":"memoryos-extraction-v2","blocks":[{"index":7,"kind":"TABLE","text":"","table":{
                  "cells":[
                    {"text":"Chỉ tiêu","columnHeader":true,"row":0,"column":0},
                    {"text":"Kết quả","columnHeader":true,"row":0,"column":1,"columnSpan":2},
                    {"text":"Doanh thu","columnHeader":true,"row":1,"column":1},
                    {"text":"Chi phí","columnHeader":true,"row":1,"column":2},
                    {"text":"Miền Bắc","rowHeader":true,"row":2,"rowSpan":2,"column":0},
                    {"text":"120 tỷ","row":2,"column":1},
                    {"text":"80 tỷ","row":2,"column":2},
                    {"text":"130 tỷ","row":3,"column":1},
                    {"text":"75 tỷ","row":3,"column":2}
                  ]}}]}
                """);

        assertEquals(2, chunks.size());
        assertTrue(chunks.getFirst().content().contains("Row: Miền Bắc"));
        assertTrue(chunks.get(0).content().contains("Kết quả / Doanh thu: 120 tỷ"));
        assertTrue(chunks.get(0).content().contains("Kết quả / Chi phí: 80 tỷ"));
        assertTrue(chunks.get(1).content().contains("Row: Miền Bắc"));
        assertTrue(chunks.get(1).content().contains("Kết quả / Doanh thu: 130 tỷ"));
        assertEquals(2, mapper.readTree(chunks.get(0).provenanceJson()).path("tableRow").asInt());
        assertEquals(3, mapper.readTree(chunks.get(1).provenanceJson()).path("tableRow").asInt());
    }

    @Test
    void aBlankHeaderOrValueAddsNoEmptyLabel() {
        // PaddleOCR-VL shape: a blank corner over the item column, a two-row header, an empty provision.
        var chunks = chunker.chunk("HUT Q1 2026", """
                {"schema":"memoryos-extraction-v2","blocks":[
                  {"index":0,"kind":"HEADING","headingLevel":2,"text":"BÁO CÁO TÌNH HÌNH TÀI CHÍNH"},
                  {"index":1,"kind":"TABLE","text":"","table":{"cells":[
                    {"text":"","row":0,"column":0},
                    {"text":"Số cuối kỳ","columnHeader":true,"row":0,"column":1,"columnSpan":2},
                    {"text":"","row":1,"column":0},
                    {"text":"Giá gốc","columnHeader":true,"row":1,"column":1},
                    {"text":"Dự phòng","columnHeader":true,"row":1,"column":2},
                    {"text":"Tiền mặt","row":2,"column":0},
                    {"text":"37.200.070.561","row":2,"column":1},
                    {"text":"","row":2,"column":2}
                  ]}}]}
                """);

        var row = chunks.stream().filter(chunk -> chunk.content().contains("Tiền mặt")).findFirst().orElseThrow();
        assertTrue(row.content().endsWith("Section: BÁO CÁO TÌNH HÌNH TÀI CHÍNH\nColumn 1: Tiền mặt\nSố cuối kỳ / Giá gốc: 37.200.070.561"),
                row.content());
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains(": \n") || chunk.content().endsWith(": ")
                || chunk.content().contains("Column 1: \n")));
    }

    @Test
    void wideTableRowsRemainBoundedAndKeepEveryValue() {
        var root = mapper.createObjectNode().put("schema", ExtractedDocument.SCHEMA);
        var blocks = root.putArray("blocks");
        var block = blocks.addObject().put("index", 9).put("kind", "TABLE");
        var cells = block.putObject("table").putArray("cells");
        for (int column = 0; column < 80; column++) {
            cells.addObject().put("text", "Cột " + column).put("columnHeader", true)
                    .put("row", 0).put("column", column);
            cells.addObject().put("text", "Giá trị " + column + " — " + "dữ liệu ".repeat(20))
                    .put("row", 1).put("column", column);
        }

        var chunks = chunker.chunk("Bảng rộng", mapper.writeValueAsString(root));

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= StructuredDocumentChunker.MAX_TOKENS));
        String combined = chunks.stream().map(DocumentChunk::content).reduce("", (left, right) -> left + "\n" + right);
        for (int column = 0; column < 80; column++) {
            assertTrue(combined.contains("Cột " + column + ": Giá trị " + column));
        }
        assertTrue(chunks.stream().allMatch(chunk ->
                mapper.readTree(chunk.provenanceJson()).path("tableRow").asInt() == 1));
    }

    @Test
    void rejectsOversizedTableSpans() {
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk("Bảng lỗi", """
                {"schema":"memoryos-extraction-v2","blocks":[{"index":0,"kind":"TABLE","text":"","table":{"cells":[
                  {"text":"Quá rộng","columnHeader":true,"row":0,"column":0,"columnSpan":2049}
                ]}}]}
                """));
    }

    @Test
    void boundsLongUnicodePassagesWithoutLosingTheLastFact() {
        String text = "Nhân viên được hoàn trả chi phí đi công tác 🚗. ".repeat(400) + "Mã kết thúc: CT-2026-999.";
        String json = mapper.writeValueAsString(Map.of("schema", ExtractedDocument.SCHEMA, "blocks",
                List.of(Map.of("kind", "PARAGRAPH", "text", text, "index", 0))));
        var chunks = chunker.chunk("Quy định", json);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= 768));
        assertTrue(chunks.getLast().content().contains("CT-2026-999"));
        assertTrue(chunks.stream().noneMatch(c -> c.content().contains("\uFFFD")));
    }

    @Test
    void rejectsUnsupportedAndEmptyArtifacts() {
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk("A", "{\"schema\":\"other\",\"blocks\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk("A", "{\"schema\":\"memoryos-extraction-v2\",\"blocks\":[]}"));
    }

    @Test
    void mergesAdjacentParagraphsIntoOneChunk() {
        var blocks = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 40; i++) {
            blocks.add(Map.of("kind", "PARAGRAPH", "index", i, "text", "Dòng số " + i + " của báo cáo.",
                    "locations", List.of(Map.of("page_no", i + 1))));
        }
        var chunks = chunker.chunk("Báo cáo", mapper.writeValueAsString(
                Map.of("schema", ExtractedDocument.SCHEMA, "blocks", blocks)));
        assertEquals(1, chunks.size());
        var chunk = chunks.getFirst();
        assertTrue(chunk.content().contains("Dòng số 0"));
        assertTrue(chunk.content().contains("Dòng số 39"));
        assertEquals(0, chunk.blockIndex());
        // Merged provenance lists every source location, not just the first block's.
        assertEquals(40, mapper.readTree(chunk.provenanceJson()).size());
    }

    @Test
    void aHeadingFlushesTheMergeSoSectionsNeverMix() {
        var chunks = chunker.chunk("Báo cáo", """
                {"schema":"memoryos-extraction-v2","blocks":[
                  {"index":0,"kind":"PARAGRAPH","text":"Đoạn mở đầu."},
                  {"index":1,"kind":"HEADING","headingLevel":1,"text":"Kết quả kinh doanh"},
                  {"index":2,"kind":"PARAGRAPH","text":"Doanh thu tăng."}]}
                """);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).content().contains("Đoạn mở đầu"));
        assertTrue(chunks.get(0).content().contains("Section") == false);
        assertTrue(chunks.get(2).content().contains("Kết quả kinh doanh"));
        assertTrue(chunks.get(2).content().contains("Doanh thu tăng"));
    }

    @Test
    void reportsTypedCodesForContentRejections() {
        var invalid = assertThrows(DocumentContentException.class,
                () -> chunker.chunk("A", "{\"schema\":\"other\",\"blocks\":[]}"));
        assertEquals("SEARCH_INDEX_ARTIFACT_INVALID", invalid.code());
        var empty = assertThrows(DocumentContentException.class,
                () -> chunker.chunk("A", "{\"schema\":\"memoryos-extraction-v2\",\"blocks\":[]}"));
        assertEquals("SEARCH_INDEX_NO_TEXT", empty.code());
    }

    @Test
    void stillRejectsDocumentsBeyondTheChunkLimit() {
        // Tables flush the merge, so alternating blocks keep one chunk per paragraph.
        var blocks = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 10_001; i++) {
            blocks.add(Map.of("kind", "PARAGRAPH", "index", i * 2, "text", "Đoạn " + i));
            blocks.add(Map.of("kind", "TABLE", "index", i * 2 + 1, "text", "",
                    "table", Map.of("cells", List.of())));
        }
        var failure = assertThrows(DocumentContentException.class, () -> chunker.chunk("Lớn",
                mapper.writeValueAsString(Map.of("schema", ExtractedDocument.SCHEMA, "blocks", blocks))));
        assertEquals("SEARCH_INDEX_CONTENT_LIMIT", failure.code());
    }
}
