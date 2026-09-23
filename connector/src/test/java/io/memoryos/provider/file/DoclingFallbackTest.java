package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import ai.docling.serve.client.DoclingServeClientException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-191. Docling failing for a reason of its own no longer stops a document the native reader
 * can read, and never lets the native reader publish a scan it could not read.
 */
class DoclingFallbackTest {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String SENTENCE = "Doanh thu hop nhat quy ba tang so voi cung ky nam truoc nho mang logistics. ";

    private final BoundedDoclingClient client = mock(BoundedDoclingClient.class);

    @TempDir Path temporary;

    @Test
    void aPdfWithATextLayerIsReadNativelyWhenDoclingIsUnreachable() throws Exception {
        docling(unreachable());
        try (var extractor = extractor()) {
            var result = extract(extractor, textPdf(2, 4), "report.pdf");
            assertTrue(result.normalizedText().contains("Doanh thu hop nhat"));
            assertFallback(result, ExtractionFailure.CONNECTION_FAILED);
        }
    }

    @Test
    void aWordDocumentIsReadNativelyWhenDoclingTimesOut() throws Exception {
        docling(new DoclingServeClientException(new java.net.http.HttpTimeoutException("slow")));
        try (var extractor = extractor()) {
            var result = extract(extractor, docx(), "minutes.docx");
            assertTrue(result.normalizedText().contains("Doanh thu hop nhat"));
            assertFallback(result, ExtractionFailure.TIMEOUT);
        }
    }

    @Test
    void aPresentationIsReadNativelyWhenDoclingAnswersWithSomethingUnusable() throws Exception {
        when(client.convertDocument(any())).thenReturn(new BoundedDoclingClient.CanonicalResponse(
                new ObjectMapper().createObjectNode(), java.util.List.of(), "partial_success",
                ai.docling.serve.api.convert.response.ResponseType.IN_BODY));
        try (var extractor = extractor()) {
            var result = extract(extractor, pptx(), "deck.pptx");
            assertTrue(result.normalizedText().contains("Doanh thu hop nhat"));
            assertFallback(result, ExtractionFailure.MALFORMED);
        }
    }

    @Test
    void aScanKeepsDoclingsFailureRatherThanPublishingItsMargins() throws Exception {
        // Three pages with a signature on one of them: text, but nowhere near a text layer. This is
        // the Tasco shape, where 254 of 260 pages carry no text at all.
        docling(unreachable());
        try (var extractor = extractor()) {
            var error = assertThrows(ExtractionException.class,
                    () -> extract(extractor, pdfWithSignatureOnly(3), "scan.pdf"));
            assertEquals(ExtractionFailure.CONNECTION_FAILED, error.failure(),
                    "the caller must see exactly what it saw before the fallback existed");
        }
    }

    @Test
    void aFailureOfTheDocumentItselfIsNotRetriedElsewhere() throws Exception {
        // Docling's own size policy refused it; a second reader would refuse the same document.
        when(client.convertDocument(any())).thenThrow(new BoundedDoclingClient.ResponseFailure(ExtractionFailure.WRITE_LIMIT));
        try (var extractor = extractor()) {
            var error = assertThrows(ExtractionException.class,
                    () -> extract(extractor, textPdf(2, 4), "large.pdf"));
            assertEquals(ExtractionFailure.WRITE_LIMIT, error.failure());
        }
    }

    @Test
    void aChatAttachmentFallsBackThroughTheBoundedSpool() throws Exception {
        when(client.convertFile(any(), any(), any(), anyBoolean())).thenThrow(new java.io.IOException("connection reset"));
        Path file = temporary.resolve("attachment.docx");
        Files.write(file, docx());
        try (var extractor = extractor()) {
            var result = extractor.extractChatFile(file, "attachment.docx", DOCX);
            assertTrue(result.normalizedText().contains("Doanh thu hop nhat"));
            assertFallback(result, ExtractionFailure.MALFORMED);
        }
    }

    @Test
    void aDoclingSuccessIsNeverMarkedAsAFallback() throws Exception {
        // The Docling path is unchanged and says so: no fallback fields on a document it produced.
        var document = new ObjectMapper().readTree("""
                {"body":{"children":[{"$ref":"#/texts/0"}]},
                 "texts":[{"label":"text","text":"Doanh thu","prov":[]}],"pages":{}}
                """);
        when(client.convertDocument(any())).thenReturn(new BoundedDoclingClient.CanonicalResponse(
                new ObjectMapper().createObjectNode().set("json_content", document), java.util.List.of(), "success",
                ai.docling.serve.api.convert.response.ResponseType.IN_BODY));
        try (var extractor = extractor()) {
            var result = extract(extractor, textPdf(1, 1), "report.pdf");
            assertEquals("docling", result.metadata().get("parser"));
            assertFalse(result.metadata().containsKey("fallback_from"));
        }
    }

    private static void assertFallback(DocumentContent result, ExtractionFailure reason) {
        assertEquals("tika", result.metadata().get("parser"));
        assertEquals("docling", result.metadata().get("fallback_from"));
        assertEquals(reason.name(), result.metadata().get("fallback_reason"));
    }

    private void docling(DoclingServeClientException failure) {
        when(client.convertDocument(any())).thenThrow(failure);
    }

    private static DoclingServeClientException unreachable() {
        return new DoclingServeClientException(new java.net.ConnectException("refused"));
    }

    private DoclingSourceContentExtractor extractor() {
        return new DoclingSourceContentExtractor(new DoclingProperties(null, null, null, 0, null, null, false, null),
                new ObjectMapper(), client);
    }

    private static DocumentContent extract(DoclingSourceContentExtractor extractor, byte[] bytes, String name)
            throws ExtractionException {
        return extractor.extract(new ByteArrayInputStream(bytes), bytes.length, name, SourceInputDescriptor.binary());
    }

    /** Every page carries several lines, comfortably above the per-page density a scan cannot reach. */
    private static byte[] textPdf(int pages, int linesPerPage) throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                var target = new PDPage();
                pdf.addPage(target);
                try (var content = new PDPageContentStream(pdf, target)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(40, 740);
                    for (int line = 0; line < linesPerPage; line++) {
                        content.showText(SENTENCE);
                        content.newLineAtOffset(0, -14);
                    }
                    content.endText();
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdfWithSignatureOnly(int pages) throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                var target = new PDPage();
                pdf.addPage(target);
                if (page == pages - 1) {
                    try (var content = new PDPageContentStream(pdf, target)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                        content.newLineAtOffset(400, 60);
                        content.showText("Ky boi: Nguyen Van A");
                        content.endText();
                    }
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(SENTENCE.repeat(3));
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (var show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var box = show.createSlide().createTextBox();
            box.setText(SENTENCE);
            show.write(out);
            return out.toByteArray();
        }
    }
}
