package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.docling.serve.api.convert.response.ResponseType;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.connector.SourceInputDescriptor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
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
            assertEquals(1, json.at("/blocks/0/provenance/0/page_no").asInt());
            assertEquals("IMAGE", json.at("/blocks/1/kind").asString());
            assertEquals("data:image/png;base64,aW1hZ2U=", json.at("/blocks/1/image/uri").asString());
            assertEquals("TABLE", json.at("/blocks/2/kind").asString());
            assertEquals("Doanh thu", json.at("/blocks/2/table/table_cells/0/text").asString());
            assertEquals("Báo cáo HROD\n\nDoanh thu", result.normalizedText());
            assertFalse(result.normalizedText().contains("data:image"));
            assertFalse(result.normalizedText().contains("aW1hZ2U="));
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
            assertEquals("1.234", mapper.readTree(result.structuredJson()).at("/blocks/0/table/table_cells/0/text").asString());
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
