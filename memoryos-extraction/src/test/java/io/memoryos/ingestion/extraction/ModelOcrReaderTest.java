package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.ai.AiException;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/** MEM-243: a scan read by a model is one call a page, and a page's text is one block on that page. */
class ModelOcrReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OcrPageReader pages = mock(OcrPageReader.class);
    private final OcrConnectionService.Connection connection = new OcrConnectionService.Connection(UUID.randomUUID(),
            UUID.randomUUID(), OcrProvider.MODEL, "", null, DataBoundary.EXTERNAL, UUID.randomUUID(), 1);
    private final OcrReader reader = new ModelOcrAdapter(pages, mapper).reader(connection, "");

    @Test
    void eachPageIsReadAsAJpegAndItsTextKeepsItsPage() throws Exception {
        byte[] pdf = scan(3);
        var sent = Collections.synchronizedList(new ArrayList<byte[]>());
        // Pages are read together, so the answer follows the picture's page, which only its size tells apart here.
        when(pages.read(eq(connection), any(), eq("image/jpeg"))).thenAnswer(call -> {
            sent.add(call.getArgument(1));
            return "Trang";
        });

        var result = reader.read(PaddleOcrVlClient.Input.of(pdf), layout(pdf), "scan.pdf", "application/pdf", 1_000_000);

        assertEquals(3, sent.size());
        for (byte[] picture : sent) assertTrue((picture[0] & 0xff) == 0xff && (picture[1] & 0xff) == 0xd8, "a JPEG");
        assertEquals("model-ocr", result.metadata().get("parser"));
        assertTrue(result.metadata().get("parser_configuration").contains("model-ocr=" + connection.modelConfigurationId()));
        var blocks = mapper.readTree(result.structuredJson()).path("blocks");
        assertEquals(3, blocks.size());
        for (int page = 0; page < 3; page++) {
            assertEquals(page + 1, blocks.get(page).path("locations").get(0).path("page_no").asInt());
            assertTrue(blocks.get(page).path("locations").get(0).path("bbox").isMissingNode(), "a model returns no layout");
        }
        assertEquals(3, mapper.readTree(result.structuredJson()).path("pages").size());
    }

    @Test
    void aPageWithoutTextAddsNothingAndADocumentWithoutAnyIsNotADocument() throws Exception {
        byte[] pdf = scan(2);
        when(pages.read(eq(connection), any(), eq("image/jpeg"))).thenReturn("");

        assertNull(reader.read(PaddleOcrVlClient.Input.of(pdf), layout(pdf), "scan.pdf", "application/pdf", 1_000_000));
        var error = assertThrows(ExtractionException.class, () -> reader.extract(PaddleOcrVlClient.Input.of(pdf),
                layout(pdf), "scan.pdf", "application/pdf", 1_000_000));
        assertEquals(ExtractionFailure.MALFORMED, error.failure());
    }

    @Test
    void aProviderThatDoesNotAnswerLeavesTheDocumentToBeReadAgain() throws Exception {
        byte[] pdf = scan(1);
        when(pages.read(eq(connection), any(), eq("image/jpeg"))).thenThrow(new IllegalStateException("upstream 503"));

        var error = assertThrows(ExtractionException.class, () -> reader.read(PaddleOcrVlClient.Input.of(pdf), layout(pdf),
                "scan.pdf", "application/pdf", 1_000_000));
        assertEquals(ExtractionFailure.CONNECTION_FAILED, error.failure());
        assertTrue(error.failure().retryable());
    }

    @Test
    void aModelThatCanNoLongerReadIsNotRetried() throws Exception {
        byte[] pdf = scan(1);
        when(pages.read(eq(connection), any(), eq("image/jpeg"))).thenThrow(AiException.invalid("The model does not read images."));

        var error = assertThrows(ExtractionException.class, () -> reader.read(PaddleOcrVlClient.Input.of(pdf), layout(pdf),
                "scan.pdf", "application/pdf", 1_000_000));
        assertEquals(ExtractionFailure.INTERNAL, error.failure());
    }

    @Test
    void anImageIsSentAsItIsAndMoreThanTwoHundredPagesAreRefusedBeforeAnyCall() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        when(pages.read(eq(connection), any(), eq("image/png"))).thenReturn("Hoá đơn 42");

        var result = reader.read(PaddleOcrVlClient.Input.of(png), null, "bill.png", "image/png", 1_000_000);

        var sent = ArgumentCaptor.forClass(byte[].class);
        verify(pages, times(1)).read(eq(connection), sent.capture(), eq("image/png"));
        assertArrayEquals(png, sent.getValue());
        assertEquals("Hoá đơn 42", result.normalizedText().strip());
        assertTrue(reader.readsImage("image/webp"));

        byte[] long_ = scan(201);
        var error = assertThrows(ExtractionException.class, () -> reader.read(PaddleOcrVlClient.Input.of(long_),
                layout(long_), "long.pdf", "application/pdf", 1_000_000));
        assertEquals(ExtractionFailure.WRITE_LIMIT, error.failure());
        verify(pages, times(1)).read(any(), any(), any());
    }

    private static PdfLayout layout(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            return PdfLayout.of(document, true);
        }
    }

    /** Small empty pages: what a scan's text layer looks like, and quick to render. */
    private static byte[] scan(int pages) throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) pdf.addPage(new PDPage(new PDRectangle(120, 80)));
            pdf.save(out);
            return out.toByteArray();
        }
    }
}
