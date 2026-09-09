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
    void preservesVietnameseSectionAndRealPageLocation() {
        var chunks = chunker.chunk("Chính sách công tác", """
                {"schema":"memoryos-extraction-v1","blocks":[
                  {"index":0,"kind":"HEADING","headingLevel":1,"text":"Thanh toán"},
                  {"index":1,"kind":"PARAGRAPH","text":"Nộp hóa đơn trong vòng 07 ngày.",
                   "provenance":[{"page_no":3,"bbox":{"l":12,"t":20}}]}]}
                """);
        assertEquals(2, chunks.size());
        var passage = chunks.get(1);
        assertTrue(passage.content().contains("Chính sách công tác"));
        assertTrue(passage.content().contains("Thanh toán"));
        assertTrue(passage.content().contains("07 ngày"));
        assertEquals(3, mapper.readTree(passage.provenanceJson()).get(0).path("page_no").asInt());
        assertEquals(1, passage.blockIndex());
        assertEquals(chunks, chunker.chunk("Chính sách công tác", """
                {"schema":"memoryos-extraction-v1","blocks":[
                  {"index":0,"kind":"HEADING","headingLevel":1,"text":"Thanh toán"},
                  {"index":1,"kind":"PARAGRAPH","text":"Nộp hóa đơn trong vòng 07 ngày.",
                   "provenance":[{"page_no":3,"bbox":{"l":12,"t":20}}]}]}
                """));
    }

    @Test
    void tableKeepsHeadersNumbersAndRowLocation() {
        var chunks = chunker.chunk("Báo cáo KPI", """
                {"schema":"memoryos-extraction-v1","blocks":[{"index":4,"kind":"TABLE","table":{
                  "table_cells":[
                    {"text":"Chỉ tiêu","column_header":true,"start_row_offset_idx":0,"start_col_offset_idx":0,"end_col_offset_idx":1},
                    {"text":"Quý II (%)","column_header":true,"start_row_offset_idx":0,"start_col_offset_idx":1,"end_col_offset_idx":2},
                    {"text":"Hoàn thành kế hoạch","row_header":true,"start_row_offset_idx":1,"start_col_offset_idx":0},
                    {"text":"90,5","start_row_offset_idx":1,"start_col_offset_idx":1}
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
                {"schema":"memoryos-extraction-v1","blocks":[{"index":7,"kind":"TABLE","table":{
                  "table_cells":[
                    {"text":"Chỉ tiêu","column_header":true,"start_row_offset_idx":0,"start_col_offset_idx":0},
                    {"text":"Kết quả","column_header":true,"start_row_offset_idx":0,"start_col_offset_idx":1,"end_col_offset_idx":3},
                    {"text":"Doanh thu","column_header":true,"start_row_offset_idx":1,"start_col_offset_idx":1},
                    {"text":"Chi phí","column_header":true,"start_row_offset_idx":1,"start_col_offset_idx":2},
                    {"text":"Miền Bắc","row_header":true,"start_row_offset_idx":2,"end_row_offset_idx":4,"start_col_offset_idx":0},
                    {"text":"120 tỷ","start_row_offset_idx":2,"start_col_offset_idx":1},
                    {"text":"80 tỷ","start_row_offset_idx":2,"start_col_offset_idx":2},
                    {"text":"130 tỷ","start_row_offset_idx":3,"start_col_offset_idx":1},
                    {"text":"75 tỷ","start_row_offset_idx":3,"start_col_offset_idx":2}
                  ]}}]}
                """);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).content().contains("Row: Miền Bắc"));
        assertTrue(chunks.get(0).content().contains("Kết quả / Doanh thu: 120 tỷ"));
        assertTrue(chunks.get(0).content().contains("Kết quả / Chi phí: 80 tỷ"));
        assertTrue(chunks.get(1).content().contains("Row: Miền Bắc"));
        assertTrue(chunks.get(1).content().contains("Kết quả / Doanh thu: 130 tỷ"));
        assertEquals(2, mapper.readTree(chunks.get(0).provenanceJson()).path("tableRow").asInt());
        assertEquals(3, mapper.readTree(chunks.get(1).provenanceJson()).path("tableRow").asInt());
    }

    @Test
    void wideTableRowsRemainBoundedAndKeepEveryValue() {
        var root = mapper.createObjectNode().put("schema", "memoryos-extraction-v1");
        var blocks = root.putArray("blocks");
        var block = blocks.addObject().put("index", 9).put("kind", "TABLE");
        var cells = block.putObject("table").putArray("table_cells");
        for (int column = 0; column < 80; column++) {
            cells.addObject().put("text", "Cột " + column).put("column_header", true)
                    .put("start_row_offset_idx", 0).put("start_col_offset_idx", column);
            cells.addObject().put("text", "Giá trị " + column + " — " + "dữ liệu ".repeat(20))
                    .put("start_row_offset_idx", 1).put("start_col_offset_idx", column);
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
                {"schema":"memoryos-extraction-v1","blocks":[{"kind":"TABLE","table":{"table_cells":[
                  {"text":"Quá rộng","column_header":true,"start_row_offset_idx":0,
                   "start_col_offset_idx":0,"end_col_offset_idx":2049}
                ]}}]}
                """));
    }

    @Test
    void boundsLongUnicodePassagesWithoutLosingTheLastFact() {
        String text = "Nhân viên được hoàn trả chi phí đi công tác 🚗. ".repeat(400) + "Mã kết thúc: CT-2026-999.";
        String json = mapper.writeValueAsString(Map.of("schema", "memoryos-extraction-v1", "blocks",
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
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk("A", "{\"schema\":\"memoryos-extraction-v1\",\"blocks\":[]}"));
    }
}
