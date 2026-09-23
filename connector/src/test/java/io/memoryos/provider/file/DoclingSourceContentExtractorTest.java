package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.docling.serve.api.convert.response.ResponseType;
import io.memoryos.ingestion.ExtractionException;
import ai.docling.serve.client.DoclingServeClientException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.connector.SourceInputDescriptor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DoclingSourceContentExtractorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BoundedDoclingClient client = mock(BoundedDoclingClient.class);

    @Test
    void excludesEmbeddedImagesAndPreservesSemanticContentAndProvenance() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/texts/0"},{"$ref":"#/pictures/0"},{"$ref":"#/tables/0"}]},
                 "texts":[{"self_ref":"#/texts/0","label":"section_header","level":1,
                   "text":"Báo cáo HROD","orig":"Báo cáo HROD","prov":[{"page_no":1,
                   "bbox":{"l":0,"t":20,"r":100,"b":0,"coord_origin":"BOTTOMLEFT"},"charspan":[0,12]}]}],
                 "tables":[{"self_ref":"#/tables/0","label":"table","data":{"num_rows":1,"num_cols":1,
                   "table_cells":[{"text":"Doanh thu","row_span":1,"col_span":1,"start_row_offset_idx":0,
                   "end_row_offset_idx":1,"start_col_offset_idx":0,"end_col_offset_idx":1,
                   "column_header":true,"row_header":false,"row_section":false}]}}],
                 "pictures":[{"self_ref":"#/pictures/0","label":"picture","image":{"mimetype":"image/png",
                   "dpi":144,"size":{"width":1,"height":1},"uri":"data:image/png;base64,aW1hZ2U="}}],"pages":{}}
                """);
        when(client.convertDocument(any())).thenReturn(response(document, "Báo cáo HROD\n![image](data:image/png;base64,aW1hZ2U=)\nDoanh thu", "success"));
        try (var extractor = extractor()) {
            var result = pdf(extractor);
            var json = mapper.readTree(result.structuredJson());
            assertEquals("HEADING", json.at("/blocks/0/kind").asString());
            assertEquals(1, json.at("/blocks/0/locations/0/page_no").asInt());
            assertEquals("IMAGE", json.at("/blocks/1/kind").asString());
            assertEquals("data:image/png;base64,aW1hZ2U=", json.at("/blocks/1/image/uri").asString());
            assertEquals("TABLE", json.at("/blocks/2/kind").asString());
            assertEquals("Doanh thu", json.at("/blocks/2/table/cells/0/text").asString());
            assertEquals("Báo cáo HROD\n\nDoanh thu", result.normalizedText());
            assertFalse(result.normalizedText().contains("data:image"));
            assertFalse(result.normalizedText().contains("aW1hZ2U="));
        }
    }

    @Test
    void writesTheMemoryOsDocumentWithoutDoclingKeys() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/texts/0"},{"$ref":"#/tables/0"}]},
                 "texts":[{"self_ref":"#/texts/0","label":"text","text":"Thuyết minh","orig":"Thuyết minh",
                   "prov":[{"page_no":2,"bbox":{"l":72.5,"t":700.25,"r":300.0,"b":680.0,"coord_origin":"BOTTOMLEFT"},
                     "charspan":[0,11]},{"page_no":0,"bbox":{"l":1,"t":1,"r":2,"b":2}}]}],
                 "tables":[{"self_ref":"#/tables/0","label":"table","prov":[{"page_no":3,
                   "bbox":{"l":10.0,"t":20.0,"r":300.0,"b":200.0,"coord_origin":"TOPLEFT"},"charspan":[0,0]}],
                   "data":{"num_rows":3,"num_cols":2,"grid":[],"table_cells":[
                     {"text":"Chỉ tiêu","row_span":2,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":2,
                      "start_col_offset_idx":0,"end_col_offset_idx":1,"column_header":true,"row_header":false,
                      "row_section":false,"bbox":{"l":1,"t":2,"r":3,"b":4,"coord_origin":"TOPLEFT"}},
                     {"text":"Số dư","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":1,"end_col_offset_idx":2,"column_header":true,"row_header":false,"row_section":false},
                     {"text":"Tiền","row_span":1,"col_span":2,"start_row_offset_idx":2,"end_row_offset_idx":3,
                      "start_col_offset_idx":0,"end_col_offset_idx":2,"column_header":false,"row_header":true,"row_section":false}
                   ]}}],
                 "pages":{"2":{"size":{"width":595.2,"height":841.8},"page_no":2,"image":{"uri":"data:image/png;base64,cGFnZQ=="}},
                          "3":{"size":{"width":841.8,"height":595.2},"page_no":3}}}
                """);
        when(client.convertDocument(any())).thenReturn(response(document, null, "success"));
        try (var extractor = extractor()) {
            var json = mapper.readTree(pdf(extractor).structuredJson());

            assertEquals("memoryos-extraction-v2", json.path("schema").asString());
            assertEquals(mapper.readTree("""
                    [{"page_no":2,"bbox":{"l":72.5,"t":700.25,"r":300.0,"b":680.0,"coord_origin":"BOTTOMLEFT"}}]"""),
                    json.at("/blocks/0/locations"), "only page_no and bbox survive; a page 0 item is not a location");
            assertEquals(mapper.readTree("""
                    [{"row":0,"column":0,"rowSpan":2,"columnHeader":true,"text":"Chỉ tiêu"},
                     {"row":0,"column":1,"columnHeader":true,"text":"Số dư"},
                     {"row":2,"column":0,"columnSpan":2,"rowHeader":true,"text":"Tiền"}]"""),
                    json.at("/blocks/1/table/cells"));
            assertEquals(3, json.at("/blocks/1/table/rowCount").asInt());
            assertEquals(2, json.at("/blocks/1/table/columnCount").asInt());
            assertEquals("TOPLEFT", json.at("/blocks/1/locations/0/bbox/coord_origin").asString());
            assertEquals(mapper.readTree("""
                    [{"page_no":2,"width":595.2,"height":841.8},{"page_no":3,"width":841.8,"height":595.2}]"""),
                    json.path("pages"));
            String written = mapper.writeValueAsString(json);
            for (String doclingKey : List.of("charspan", "table_cells", "start_row_offset_idx", "column_header",
                    "num_rows", "grid", "row_section", "cGFnZQ==")) {
                assertFalse(written.contains(doclingKey), doclingKey);
            }
        }
    }

    @Test
    void rendersTableOnlyDocumentInRowAndColumnOrderWithoutTextExport() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/tables/0"}]},
                 "tables":[{"self_ref":"#/tables/0","label":"table","data":{"num_rows":2,"num_cols":2,
                   "table_cells":[
                     {"text":"1.234","row_span":1,"col_span":1,"start_row_offset_idx":1,"end_row_offset_idx":2,
                      "start_col_offset_idx":1,"end_col_offset_idx":2,"column_header":false,"row_header":false,"row_section":false},
                     {"text":"Chỉ tiêu","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":0,"end_col_offset_idx":1,"column_header":true,"row_header":false,"row_section":false},
                     {"text":"Doanh thu","row_span":1,"col_span":1,"start_row_offset_idx":1,"end_row_offset_idx":2,
                      "start_col_offset_idx":0,"end_col_offset_idx":1,"column_header":false,"row_header":true,"row_section":false},
                     {"text":"2024","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":1,"end_col_offset_idx":2,"column_header":true,"row_header":false,"row_section":false}
                   ]}}],"pages":{}}
                """);
        when(client.convertDocument(any())).thenReturn(response(document, null, "success"));
        try (var extractor = extractor()) {
            var result = pdf(extractor);
            assertEquals("Chỉ tiêu\t2024\nDoanh thu\t1.234", result.normalizedText());
            assertEquals("1.234", mapper.readTree(result.structuredJson()).at("/blocks/0/table/cells/0/text").asString());
        }
    }

    @Test
    void preservesMissingLeadingAndIntermediateTableColumns() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/tables/0"}]},
                 "tables":[{"self_ref":"#/tables/0","label":"table","data":{"num_rows":3,"num_cols":3,
                   "table_cells":[
                     {"text":"2025","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":1,"end_col_offset_idx":2,"column_header":true,"row_header":false,"row_section":false},
                     {"text":"2024","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":2,"end_col_offset_idx":3,"column_header":true,"row_header":false,"row_section":false},
                     {"text":"Doanh thu","row_span":1,"col_span":1,"start_row_offset_idx":1,"end_row_offset_idx":2,
                      "start_col_offset_idx":0,"end_col_offset_idx":1,"column_header":false,"row_header":true,"row_section":false},
                     {"text":"1.234","row_span":1,"col_span":1,"start_row_offset_idx":1,"end_row_offset_idx":2,
                      "start_col_offset_idx":2,"end_col_offset_idx":3,"column_header":false,"row_header":false,"row_section":false},
                     {"text":"3.456","row_span":1,"col_span":1,"start_row_offset_idx":2,"end_row_offset_idx":3,
                      "start_col_offset_idx":1,"end_col_offset_idx":2,"column_header":false,"row_header":false,"row_section":false}
                   ]}}],"pages":{}}
                """);
        when(client.convertDocument(any())).thenReturn(response(document, null, "success"));
        try (var extractor = extractor()) {
            assertEquals("\t2025\t2024\nDoanh thu\t\t1.234\n\t3.456", pdf(extractor).normalizedText());
        }
    }

    @Test
    void boundsSparseColumnPaddingBeforeAllocatingText() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/tables/0"}]},
                 "tables":[{"self_ref":"#/tables/0","label":"table","data":{"num_rows":1,"num_cols":2147483647,
                   "table_cells":[
                     {"text":"1.234","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":2147483646,"end_col_offset_idx":2147483647,
                      "column_header":false,"row_header":false,"row_section":false}
                   ]}}],"pages":{}}
                """);
        when(client.convertDocument(any())).thenReturn(response(document, null, "success"));
        try (var extractor = extractor()) {
            assertEquals(ExtractionFailure.WRITE_LIMIT,
                    assertThrows(ExtractionException.class, () -> pdf(extractor)).failure());
        }
    }

    @Test
    void rejectsImageOnlyDocumentEvenWhenTextExportContainsImageMarkup() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/pictures/0"}]},
                 "pictures":[{"self_ref":"#/pictures/0","label":"picture","image":{"mimetype":"image/png",
                   "dpi":144,"size":{"width":1,"height":1},"uri":"data:image/png;base64,aW1hZ2U="}}],"pages":{}}
                """);
        when(client.convertDocument(any())).thenReturn(response(document, "![image](data:image/png;base64,aW1hZ2U=)", "success"));
        try (var extractor = extractor()) {
            assertEquals(ExtractionFailure.MALFORMED,
                    assertThrows(ExtractionException.class, () -> pdf(extractor)).failure());
        }
    }

    @Test
    void rejectsTableWithoutSemanticCellText() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/tables/0"}]},
                 "tables":[{"self_ref":"#/tables/0","label":"table","data":{"num_rows":1,"num_cols":2,
                   "table_cells":[
                     {"text":" ","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":0,"end_col_offset_idx":1,"column_header":true,"row_header":false,"row_section":false},
                     {"text":"","row_span":1,"col_span":1,"start_row_offset_idx":0,"end_row_offset_idx":1,
                      "start_col_offset_idx":1,"end_col_offset_idx":2,"column_header":true,"row_header":false,"row_section":false}
                   ]}}],"pages":{}}
                """);
        when(client.convertDocument(any())).thenReturn(response(document, "| | |", "success"));
        try (var extractor = extractor()) {
            assertEquals(ExtractionFailure.MALFORMED,
                    assertThrows(ExtractionException.class, () -> pdf(extractor)).failure());
        }
    }

    @Test
    void rejectsSemanticTextOverCharacterLimitWithoutTextExport() throws Exception {
        var document = mapper.readTree("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/texts/0"}]},
                 "texts":[{"self_ref":"#/texts/0","label":"text","text":"%s","orig":""}],"pages":{}}
                """.formatted("a".repeat(2_000_001)));
        when(client.convertDocument(any())).thenReturn(response(document, null, "success"));
        try (var extractor = extractor()) {
            assertEquals(ExtractionFailure.WRITE_LIMIT,
                    assertThrows(ExtractionException.class, () -> pdf(extractor)).failure());
        }
    }

    @Test
    void rejectsPartialSuccessInsteadOfPublishingIncompleteDocument() {
        when(client.convertDocument(any())).thenReturn(response(null, null, "partial_success"));
        try (var extractor = extractor()) {
            assertEquals(ExtractionFailure.MALFORMED,
                    assertThrows(ExtractionException.class, () -> pdf(extractor)).failure());
        }
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void distinguishesConnectionFailuresFromProcessingTimeoutsWithoutResubmitting(
            Exception cause, ExtractionFailure expected) {
        when(client.convertDocument(any())).thenThrow(new DoclingServeClientException(cause));
        try (var extractor = extractor()) {
            var error = assertThrows(ExtractionException.class, () -> pdf(extractor));
            assertEquals(expected, error.failure());
            assertNull(error.getCause());
            assertFalse(error.toString().contains("private-endpoint"));
            verify(client, times(1)).convertDocument(any());
        }
    }

    private static java.util.stream.Stream<Arguments> transportFailures() {
        return java.util.stream.Stream.of(
                Arguments.of(new java.net.http.HttpConnectTimeoutException("private-endpoint"), ExtractionFailure.CONNECTION_FAILED),
                Arguments.of(new java.net.ConnectException("private-endpoint"), ExtractionFailure.CONNECTION_FAILED),
                Arguments.of(new java.net.UnknownHostException("private-endpoint"), ExtractionFailure.CONNECTION_FAILED),
                Arguments.of(new java.net.http.HttpTimeoutException("private-endpoint"), ExtractionFailure.TIMEOUT),
                Arguments.of(new java.net.SocketException("private-endpoint"), ExtractionFailure.INTERNAL));
    }


    @Test
    void plainTextDoesNotCallDoclingAndInputSizeIsValidated() throws Exception {
        try (var extractor = extractor()) {
            byte[] text = "MemoryOS indexing test".getBytes(StandardCharsets.UTF_8);
            assertTrue(extractor.extract(new ByteArrayInputStream(text), text.length, "note.txt", SourceInputDescriptor.binary())
                    .normalizedText().contains("MemoryOS indexing test"));
            assertThrows(ExtractionException.class,
                    () -> extractor.extract(new ByteArrayInputStream(text), text.length + 1, "note.txt", SourceInputDescriptor.binary()));
            verifyNoInteractions(client);
        }
    }

    private BoundedDoclingClient.CanonicalResponse response(JsonNode document, String text, String status) {
        return new BoundedDoclingClient.CanonicalResponse(
                mapper.createObjectNode().put("text_content", text).set("json_content", document),
                List.of(), status, ResponseType.IN_BODY);
    }

    private DoclingSourceContentExtractor extractor() {
        return new DoclingSourceContentExtractor(new DoclingProperties(null, null, null, 0, null, null, false, null), mapper, client);
    }

    @Test
    void encryptedAndMalformedPdfAreRejectedBeforeSendingToService() throws Exception {
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "reader",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            pdf.save(out);
            bytes = out.toByteArray();
        }
        try (var extractor = extractor()) {
            assertEquals(ExtractionFailure.ENCRYPTED, assertThrows(ExtractionException.class,
                    () -> extractor.extract(new ByteArrayInputStream(bytes), bytes.length, "encrypted.pdf", SourceInputDescriptor.binary())).failure());
            byte[] broken = "%PDF-1.7\nbroken".getBytes(StandardCharsets.UTF_8);
            assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                    () -> extractor.extract(new ByteArrayInputStream(broken), broken.length, "broken.pdf", SourceInputDescriptor.binary())).failure());
            verifyNoInteractions(client);
        }
    }

    private io.memoryos.document.DocumentContent pdf(DoclingSourceContentExtractor extractor) throws Exception {
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(out);
            bytes = out.toByteArray();
        }
        return extractor.extract(new ByteArrayInputStream(bytes), bytes.length, "file.pdf", SourceInputDescriptor.binary());
    }
}
