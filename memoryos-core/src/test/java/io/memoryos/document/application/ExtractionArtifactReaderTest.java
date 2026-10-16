package io.memoryos.document.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentContentException;
import io.memoryos.document.ExtractedDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stored `memoryos-extraction-v1` artifacts must chunk as they did before the v2 model. The
 * `*.convention-v2.json` expectations were recorded by running the convention-v2 chunker on the
 * v1 fixtures before the model existed; they are not derived from the code under test.
 */
class ExtractionArtifactReaderTest {
    private static final String TITLE = "Tài liệu kiểm thử";
    /** The Docling table without column headers; its rendering changed on purpose, see below. */
    private static final int HEADERLESS_TABLE_BLOCK = 8;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredDocumentChunker chunker = new StructuredDocumentChunker(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"spreadsheet", "google-docs", "plain"})
    void upgradedNativeArtifactsChunkExactlyAsBefore(String fixture) throws IOException {
        var expected = (ArrayNode) mapper.readTree(resource(fixture + ".convention-v2.json"));
        var actual = chunker.chunk(TITLE, resource(fixture + ".json"));

        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            assertSameChunk(expected.get(i), actual.get(i));
            assertEquals(expected.get(i).path("provenanceJson").asString(), actual.get(i).provenanceJson());
        }
    }

    @Test
    void upgradedDoclingArtifactChunksAsBeforeWithOnlyContractLocationKeys() throws IOException {
        var expected = (ArrayNode) mapper.readTree(resource("docling.convention-v2.json"));
        var actual = chunker.chunk(TITLE, resource("docling.json"));

        assertEquals(expected.size(), actual.size());
        int compared = 0;
        for (int i = 0; i < actual.size(); i++) {
            if (actual.get(i).blockIndex() == HEADERLESS_TABLE_BLOCK) continue;
            assertSameChunk(expected.get(i), actual.get(i));
            // Docling `charspan` is dropped; one location is an object and none is null.
            assertEquals(contractProvenance(mapper.readTree(expected.get(i).path("provenanceJson").asString())),
                    mapper.readTree(actual.get(i).provenanceJson()));
            compared++;
        }
        assertEquals(expected.size() - 2, compared);
        assertTrue(actual.stream().noneMatch(chunk -> chunk.provenanceJson().contains("charspan")));
    }

    @Test
    void aDoclingTableWithoutColumnHeadersNowReadsByCellAddress() throws IOException {
        var rows = chunker.chunk(TITLE, resource("docling.json")).stream()
                .filter(chunk -> chunk.blockIndex() == HEADERLESS_TABLE_BLOCK).toList();

        // Convention v2 read it as "Row: Phải thu khách hàng\nColumn 1: …\nColumn 2: 45.000".
        assertEquals(List.of(
                "Title: Tài liệu kiểm thử\nSection: Báo cáo tài chính quý I > Thuyết minh\n[A1] Phải thu khách hàng\n[B1] 45.000",
                "Title: Tài liệu kiểm thử\nSection: Báo cáo tài chính quý I > Thuyết minh\n[A2] Trả trước người bán\n[B2] 7.500"),
                rows.stream().map(DocumentChunk::content).toList());
        assertEquals(1, mapper.readTree(rows.get(1).provenanceJson()).path("tableRow").asInt());
        assertEquals(3, mapper.readTree(rows.get(1).provenanceJson()).path("source").path("page_no").asInt());
    }

    @Test
    void googleSheetsProvenanceKeepsTheSheetIndexInsteadOfTheProviderProperties() throws IOException {
        var expected = (ArrayNode) mapper.readTree(resource("google-sheets.convention-v2.json"));
        var actual = chunker.chunk(TITLE, resource("google-sheets.json"));

        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            assertSameChunk(expected.get(i), actual.get(i));
            assertEquals("{\"source\":{\"sheetIndex\":0},\"tableRow\":" + i + "}", actual.get(i).provenanceJson());
        }
    }

    @Test
    void sharePointPagesGainTheirHeadingsListsAndTables() throws IOException {
        // Convention v2 did not recognise the lowercase kinds: one merged paragraph and no table.
        var chunks = chunker.chunk(TITLE, resource("sharepoint.json"));

        assertEquals(List.of(
                "Title: Tài liệu kiểm thử\nSection: Quy trình mua sắm\nQuy trình mua sắm",
                "Title: Tài liệu kiểm thử\nSection: Quy trình mua sắm\nMọi đề nghị mua sắm phải được phê duyệt.\n\nLập phiếu đề nghị",
                "Title: Tài liệu kiểm thử\nSection: Quy trình mua sắm\n[A1] Hạn mức\n[B1] Người duyệt",
                "Title: Tài liệu kiểm thử\nSection: Quy trình mua sắm\n[A2] Dưới 50 triệu\n[B2] Trưởng phòng"),
                chunks.stream().map(DocumentChunk::content).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"docling", "spreadsheet", "google-docs", "google-sheets", "plain", "sharepoint"})
    void aWrittenV2ArtifactChunksLikeTheV1ArtifactItCameFrom(String fixture) throws IOException {
        String v1 = resource(fixture + ".json");
        ExtractedDocument document = ExtractionArtifactReader.read(mapper, v1);
        String v2 = mapper.writeValueAsString(document);

        assertEquals(ExtractedDocument.SCHEMA, mapper.readTree(v2).path("schema").asString());
        assertFalse(v2.contains("table_cells") || v2.contains("charspan") || v2.contains("start_row_offset_idx"));
        assertEquals(document, ExtractionArtifactReader.read(mapper, v2));
        assertEquals(chunker.chunk(TITLE, v1), chunker.chunk(TITLE, v2));
    }

    @Test
    void upgradeKeepsPagesImagesOrientationAndFinancialChecks() throws IOException {
        JsonNode document = mapper.valueToTree(ExtractionArtifactReader.read(mapper, resource("docling.json")));

        assertEquals(mapper.readTree("""
                [{"page_no":1,"width":595.2,"height":841.8},{"page_no":2,"width":595.2,"height":841.8},
                 {"page_no":3,"width":595.2,"height":841.8},{"page_no":4,"width":841.8,"height":595.2}]"""),
                document.path("pages"));
        assertEquals("data:image/png;base64,aW1hZ2U=", document.at("/blocks/6/image/uri").asString());
        assertEquals(90, document.at("/page_orientation/4/correction").asInt());
        assertTrue(document.path("financial_checks").isArray());
        var cell = document.at("/blocks/5/table/cells/1");
        assertEquals(mapper.readTree("""
                {"row":0,"column":1,"columnSpan":2,"columnHeader":true,"text":"Số dư"}"""), cell);
    }

    @Test
    void rejectsUnknownSchemasAndMalformedV2Artifacts() {
        for (String json : List.of("{\"schema\":\"other\",\"blocks\":[]}", "[]",
                "{\"schema\":\"memoryos-extraction-v2\",\"blocks\":[{\"index\":0,\"text\":\"no kind\"}]}",
                "{\"schema\":\"memoryos-extraction-v2\",\"blocks\":[{\"index\":0,\"kind\":\"SECTION\",\"text\":\"x\"}]}")) {
            var failure = assertThrows(DocumentContentException.class, () -> chunker.chunk("A", json));
            assertEquals("SEARCH_INDEX_ARTIFACT_INVALID", failure.code());
        }
    }

    private void assertSameChunk(JsonNode expected, DocumentChunk actual) {
        assertEquals(expected.path("ordinal").asInt(), actual.ordinal());
        assertEquals(expected.path("content").asString(), actual.content());
        assertEquals(mapper.convertValue(expected.path("headings"), List.class), actual.headings());
        assertEquals(expected.path("blockIndex").asInt(), actual.blockIndex());
        assertEquals(expected.path("part").asInt(), actual.part());
        assertEquals(expected.path("contentSha256").asString(), actual.contentSha256());
        assertEquals(expected.path("tokenCount").asInt(), actual.tokenCount());
    }

    /** Recorded Docling provenance reduced to the contract: a block's `prov` array, or merged arrays. */
    private JsonNode contractProvenance(JsonNode recorded) {
        if (recorded.isObject() && recorded.has("tableRow")) {
            var table = (ObjectNode) recorded.deepCopy();
            table.set("source", blockProvenance(recorded.path("source")));
            return table;
        }
        boolean merged = recorded.isArray() && !recorded.isEmpty() && recorded.get(0).isArray();
        if (!merged) return blockProvenance(recorded);
        var blocks = mapper.createArrayNode();
        recorded.forEach(block -> blocks.add(blockProvenance(block)));
        return blocks;
    }

    private JsonNode blockProvenance(JsonNode prov) {
        var locations = mapper.createArrayNode();
        for (JsonNode item : prov) locations.add(((ObjectNode) item.deepCopy()).without("charspan"));
        if (locations.isEmpty()) return mapper.nullNode();
        return locations.size() == 1 ? locations.get(0) : locations;
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = ExtractionArtifactReaderTest.class.getResourceAsStream("/extraction-v1/" + name)) {
            if (input == null) throw new IOException("missing fixture " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
