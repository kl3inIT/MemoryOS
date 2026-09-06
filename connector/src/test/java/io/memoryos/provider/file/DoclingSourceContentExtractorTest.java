package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.docling.core.DoclingDocument;
import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.response.DocumentResponse;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DoclingSourceContentExtractorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DoclingServeApi client = mock(DoclingServeApi.class);

    @Test
    void preservesOrderedHeadingTableAndProvenance() throws Exception {
        var document = mapper.readValue("""
                {"schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                 "body":{"self_ref":"#/body","children":[{"$ref":"#/texts/0"},{"$ref":"#/tables/0"}]},
                 "texts":[{"self_ref":"#/texts/0","label":"section_header","level":1,
                   "text":"Báo cáo HROD","orig":"Báo cáo HROD","prov":[{"page_no":1,
                   "bbox":{"l":0,"t":20,"r":100,"b":0,"coord_origin":"BOTTOMLEFT"},"charspan":[0,12]}]}],
                 "tables":[{"self_ref":"#/tables/0","label":"table","data":{"num_rows":1,"num_cols":1,
                   "table_cells":[{"text":"Doanh thu","row_span":1,"col_span":1,"start_row_offset_idx":0,
                   "end_row_offset_idx":1,"start_col_offset_idx":0,"end_col_offset_idx":1,
                   "column_header":true,"row_header":false,"row_section":false}]}}],"pages":{}}
                """, DoclingDocument.class);
        when(client.convertSource(any())).thenReturn(InBodyConvertDocumentResponse.builder().status("success")
                .document(DocumentResponse.builder().jsonContent(document).textContent("Báo cáo HROD\nDoanh thu").build())
                .build());
        try (var extractor = extractor()) {
            var result = pdf(extractor);
            var json = mapper.readTree(result.structuredJson());
            assertEquals("HEADING", json.at("/blocks/0/kind").asString());
            assertEquals(1, json.at("/blocks/0/provenance/0/page_no").asInt());
            assertEquals("TABLE", json.at("/blocks/1/kind").asString());
            assertEquals("Doanh thu", json.at("/blocks/1/table/table_cells/0/text").asString());
            assertTrue(result.metadata().get("parser_configuration").contains("docling-java=0.6.5"));
        }
    }

    @Test
    void rejectsPartialSuccessInsteadOfPublishingIncompleteDocument() {
        when(client.convertSource(any())).thenReturn(InBodyConvertDocumentResponse.builder()
                .status("partial_success").build());
        try (var extractor = extractor()) {
            assertEquals(ExtractionFailure.MALFORMED,
                    assertThrows(ExtractionException.class, () -> pdf(extractor)).failure());
        }
    }

    @Test
    void serviceFailureDoesNotLeakResponseAndDoesNotFallBackToTika() {
        when(client.convertSource(any())).thenThrow(new IllegalStateException("secret response body"));
        try (var extractor = extractor()) {
            var error = assertThrows(IllegalStateException.class, () -> pdf(extractor));
            assertEquals("Docling request failed", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void plainTextDoesNotCallDoclingAndInputSizeIsValidated() throws Exception {
        try (var extractor = extractor()) {
            byte[] text = "MemoryOS indexing test".getBytes(StandardCharsets.UTF_8);
            assertTrue(extractor.extract(new ByteArrayInputStream(text), text.length, "note.txt")
                    .normalizedText().contains("MemoryOS indexing test"));
            assertThrows(ExtractionException.class,
                    () -> extractor.extract(new ByteArrayInputStream(text), text.length + 1, "note.txt"));
            verifyNoInteractions(client);
        }
    }

    private DoclingSourceContentExtractor extractor() {
        return new DoclingSourceContentExtractor(new DoclingProperties(null, null, null, 0), mapper, client);
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
                    () -> extractor.extract(new ByteArrayInputStream(bytes), bytes.length, "encrypted.pdf")).failure());
            byte[] broken = "%PDF-1.7\nbroken".getBytes(StandardCharsets.UTF_8);
            assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                    () -> extractor.extract(new ByteArrayInputStream(broken), broken.length, "broken.pdf")).failure());
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
        return extractor.extract(new ByteArrayInputStream(bytes), bytes.length, "file.pdf");
    }
}
