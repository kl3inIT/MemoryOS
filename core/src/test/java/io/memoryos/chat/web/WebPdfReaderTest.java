package io.memoryos.chat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class WebPdfReaderTest {
    private static byte[] pdf(int pages, String text) throws IOException {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("Public report");
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pages; i++) {
                var page = new PDPage();
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(font, 12); content.newLineAtOffset(50, 700);
                    content.showText(text); content.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    @Test void normalProviderReadExtractsPdfTextWithoutCredentialsOrOcr() throws Exception {
        var http = mock(WebHttp.class);
        var connections = mock(WebConnectionService.class);
        when(http.page(anyString(), any())).thenReturn(new WebHttp.Response(200, "application/pdf", pdf(1, "Revenue 42 million"), null));
        var meters = new SimpleMeterRegistry();
        try {
            var result = new WebProviderClient(http, connections, meters).read(null, "https://example.com/report.pdf", () -> {});
            assertEquals("Public report", result.title());
            assertTrue(result.text().contains("Revenue 42 million"));
            assertFalse(result.text().contains("truncated"));
            verifyNoInteractions(connections);
        } finally { meters.close(); }
    }

    @Test void boundsPagesAndOutputAndMarksPartialContent() throws Exception {
        var pages = WebPdfReader.read(pdf(51, "Page text"), "https://example.com/report.pdf", () -> {});
        assertTrue(pages.text().contains("truncated"));
        var text = WebPdfReader.read(pdf(2, "report ".repeat(2000)), "https://example.com/report.pdf", () -> {});
        assertTrue(text.text().contains("truncated"));
        assertTrue(text.text().length() < 16100);
    }

    @Test void unreadableMalformedAndCanceledPdfNeverBecomeInventedEvidence() throws Exception {
        assertThrows(IOException.class, () -> WebPdfReader.read(new byte[] {1, 2}, "https://example.com", () -> {}));
        assertThrows(IOException.class, () -> WebPdfReader.read(pdf(1, ""), "https://example.com", () -> {}));
        var bytes = pdf(1, "Real text");
        assertThrows(CancellationException.class, () -> WebPdfReader.read(bytes, "https://example.com", () -> { throw new CancellationException(); }));
    }
}
