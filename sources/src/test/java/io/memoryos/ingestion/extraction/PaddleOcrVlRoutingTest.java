package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.response.ResponseType;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-192. The provider is chosen by the text layer, not the file extension: a scan or an image goes
 * to PaddleOCR-VL when it is configured, a text layer to Docling without OCR, and without
 * PaddleOCR-VL Docling keeps reading scans with its own OCR. A PaddleOCR-VL failure is final.
 */
class PaddleOcrVlRoutingTest {
    private static final String SENTENCE = "Doanh thu hop nhat quy ba tang so voi cung ky nam truoc nho mang logistics. ";
    private final ObjectMapper mapper = new ObjectMapper();
    private final BoundedDoclingClient docling = mock(BoundedDoclingClient.class);
    private final AtomicInteger paddleCalls = new AtomicInteger();
    private HttpServer server;

    @TempDir Path temporary;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void aScannedPdfIsReadByPaddleOcrVl() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        try (var extractor = withPaddle()) {
            var result = extract(extractor, scan(1), "report.pdf");

            assertEquals("paddleocr-vl", result.metadata().get("parser"));
            assertTrue(result.metadata().get("parser_configuration").startsWith("memoryos-extraction-v2;paddleocr-vl=PaddleOCR-VL-1.6@"));
            assertFalse(result.metadata().containsKey("fallback_from"));
            assertTrue(result.normalizedText().contains("11.030.333.521.790"));
            var document = mapper.readTree(result.structuredJson());
            assertEquals("memoryos-extraction-v2", document.path("schema").asString());
            assertEquals(842.04, document.path("pages").get(0).path("width").asDouble(), 0.01);
            assertEquals("BOTTOMLEFT", document.path("blocks").get(1).path("locations").get(0).path("bbox").path("coord_origin").asString());
            assertTrue(document.path("financial_checks").isArray());
            assertEquals(1, paddleCalls.get());
            verifyNoInteractions(docling);
        }
    }

    @Test
    void aTextReportWithOneScannedPageIsReadByPaddleOcrVl() throws Exception {
        // The text page alone carries more than both pages' threshold together; the page without text decides.
        serve(200, fixture("sideways-and-upright-page.json"));
        doclingAnswers();
        try (var extractor = withPaddle()) {
            var result = extract(extractor, textThenScan(), "report.pdf");

            assertEquals("paddleocr-vl", result.metadata().get("parser"));
            assertEquals(1, paddleCalls.get());
            verifyNoInteractions(docling);
        }
    }

    @Test
    void aTableBlockWithoutCellsKeepsItsTextAsAParagraph() throws Exception {
        serve(200, """
                {"logId":"table-text","errorCode":0,"errorMsg":"Success","result":{"layoutParsingResults":[
                 {"prunedResult":{"width":1685,"height":1191,"parsing_res_list":[
                  {"block_label":"table","block_content":"Tổng cộng tài sản 12.345.678","block_bbox":[10,10,500,500]},
                  {"block_label":"table","block_content":"<table></table>","block_bbox":[10,600,500,900]}]}}]}}
                """);
        try (var extractor = withPaddle()) {
            var result = extract(extractor, scan(1), "scan.pdf");

            var blocks = mapper.readTree(result.structuredJson()).path("blocks");
            assertEquals(1, blocks.size(), "a table block with neither cells nor text adds nothing");
            assertEquals("PARAGRAPH", blocks.get(0).path("kind").asString());
            assertEquals("Tổng cộng tài sản 12.345.678", blocks.get(0).path("text").asString());
            assertTrue(result.normalizedText().contains("Tổng cộng tài sản 12.345.678"));
        }
    }

    @Test
    void aPdfWithATextLayerGoesToDoclingWithoutOcr() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        doclingAnswers();
        try (var extractor = withPaddle()) {
            var result = extract(extractor, textPdf(2), "report.pdf");

            assertEquals("docling", result.metadata().get("parser"));
            assertTrue(result.metadata().get("parser_configuration").contains(";ocr=none;"));
            assertFalse(submittedOptions().path("do_ocr").asBoolean(true));
            assertFalse(submittedOptions().has("ocr_engine"), "no engine is named for OCR that does not run");
            assertEquals(0, paddleCalls.get());
        }
    }

    @Test
    void withoutPaddleOcrVlDoclingKeepsReadingScansWithItsOwnOcr() throws Exception {
        doclingAnswers();
        try (var extractor = new DoclingSourceContentExtractor(properties(), mapper, docling)) {
            var result = extract(extractor, scan(2), "scan.pdf");

            assertEquals("docling", result.metadata().get("parser"));
            assertTrue(result.metadata().get("parser_configuration").contains(";ocr=easyocr:vi,en;force=false;"));
            assertTrue(submittedOptions().path("do_ocr").asBoolean(false));
            assertEquals("easyocr", submittedOptions().path("ocr_engine").asString());
        }
    }

    @Test
    void aPaddleOcrVlFailureOnAScanIsNotReplacedByAnotherReader() throws Exception {
        serve(503, "{\"errorCode\":503,\"errorMsg\":\"busy\"}");
        doclingAnswers();
        try (var extractor = withPaddle()) {
            var error = assertThrows(ExtractionException.class, () -> extract(extractor, scan(1), "scan.pdf"));

            assertEquals(ExtractionFailure.INTERNAL, error.failure());
            assertEquals(1, paddleCalls.get(), "sent once, not retried");
            verifyNoInteractions(docling);
        }
    }

    @Test
    void anUnreachablePaddleOcrVlLeavesTheScanFailedForReindex() throws Exception {
        serve(200, "{}");
        var closed = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.stop(0);
        server = null;
        var paddle = new PaddleOcrVlExtractor(new PaddleOcrVlProperties(closed, Duration.ofSeconds(10), 200, null, null), mapper);
        try (var extractor = new DoclingSourceContentExtractor(properties(), mapper, docling, paddle)) {
            var error = assertThrows(ExtractionException.class, () -> extract(extractor, scan(1), "scan.pdf"));
            assertEquals(ExtractionFailure.CONNECTION_FAILED, error.failure());
            verifyNoInteractions(docling);
        }
    }

    @Test
    void aBlankLayoutIsMalformedForASource() throws Exception {
        serve(200, blankAnswer());
        try (var extractor = withPaddle()) {
            var error = assertThrows(ExtractionException.class, () -> extract(extractor, scan(1), "scan.pdf"));
            assertEquals(ExtractionFailure.MALFORMED, error.failure());
            verifyNoInteractions(docling);
        }
    }

    @Test
    void aSourceImageIsReadByPaddleOcrVlThroughTheRouter() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        try (var extractor = withPaddle()) {
            byte[] png = png();
            var result = new SourceContentExtractorRouter(extractor, mapper)
                    .extract(new ByteArrayInputStream(png), png.length, "scan.png", SourceInputDescriptor.binary());

            assertEquals("paddleocr-vl", result.metadata().get("parser"));
            assertEquals("image/png", result.mediaType());
            var document = mapper.readTree(result.structuredJson());
            assertTrue(document.path("pages").isMissingNode() || document.path("pages").isEmpty());
            var location = document.path("blocks").get(0).path("locations").get(0);
            assertEquals(1, location.path("page_no").asInt());
            assertTrue(location.path("bbox").isMissingNode(), "an image has no page in points to place a box on");
        }
    }

    @Test
    void withoutPaddleOcrVlAnImageIsNotReadAsASource() throws Exception {
        try (var extractor = new DoclingSourceContentExtractor(properties(), mapper, docling)) {
            byte[] png = png();
            var error = assertThrows(ExtractionException.class, () -> new SourceContentExtractorRouter(extractor, mapper)
                    .extract(new ByteArrayInputStream(png), png.length, "scan.png", SourceInputDescriptor.binary()));
            assertEquals(ExtractionFailure.UNSUPPORTED, error.failure());
            assertFalse(extractor.readsImage("image/png"));
        }
    }

    @Test
    void aScannedChatAttachmentIsReadByPaddleOcrVlFromItsSpool() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        Path file = temporary.resolve("attachment.pdf");
        Files.write(file, scan(1));
        try (var extractor = withPaddle()) {
            var result = extractor.extractChatFile(file, "attachment.pdf", "application/pdf");
            assertEquals("paddleocr-vl", result.metadata().get("parser"));
            assertTrue(result.metadata().get("parser_configuration").contains(";maxInput=262144000;"));
            verifyNoInteractions(docling);
        }
    }

    @Test
    void aTextChatAttachmentGoesToDoclingWithoutOcr() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        when(docling.convertFile(any(), any(), any(), anyBoolean())).thenReturn(doclingDocument());
        Path file = temporary.resolve("attachment.pdf");
        Files.write(file, textPdf(1));
        try (var extractor = withPaddle()) {
            var result = extractor.extractChatFile(file, "attachment.pdf", "application/pdf");
            assertEquals("docling", result.metadata().get("parser"));
            verify(docling).convertFile(any(), any(), any(), eq(false));
            assertEquals(0, paddleCalls.get());
        }
    }

    @Test
    void aChatPhotographWithoutTextKeepsItsImageReader() throws Exception {
        serve(200, blankAnswer());
        Path file = temporary.resolve("photo.png");
        Files.write(file, png());
        try (var extractor = withPaddle()) {
            assertTrue(extractor.readsImage("image/png"));
            assertNull(extractor.readChatImage(file, "photo.png", "image/png"),
                    "no text is not a failure for an attachment; the ordinary image description follows");
            assertEquals(1, paddleCalls.get());
        }
    }

    @Test
    void aChatImageFailureIsNotHidden() throws Exception {
        serve(500, "{\"errorCode\":500}");
        Path file = temporary.resolve("scan.png");
        Files.write(file, png());
        try (var extractor = withPaddle()) {
            var error = assertThrows(ExtractionException.class, () -> extractor.readChatImage(file, "scan.png", "image/png"));
            assertEquals(ExtractionFailure.INTERNAL, error.failure());
        }
    }

    @Test
    void anOversizedImageIsRefusedBeforeItIsSent() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        try (var extractor = withPaddle()) {
            byte[] huge = new byte[20_971_521];
            byte[] png = png();
            System.arraycopy(png, 0, huge, 0, png.length);
            var error = assertThrows(ExtractionException.class,
                    () -> extractor.extract(huge, "huge.png", "image/png", SourceInputDescriptor.binary()));
            assertEquals(ExtractionFailure.WRITE_LIMIT, error.failure());
            assertEquals(0, paddleCalls.get());
        }
    }

    @Test
    void aTiffWithMoreFramesThanThePageLimitIsRefusedBeforeItIsSent() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        try (var extractor = withPaddle(2)) {
            byte[] tiff = tiff(frame(40, 30), frame(40, 30), frame(40, 30));
            var error = assertThrows(ExtractionException.class,
                    () -> extractor.extract(tiff, "scan.tiff", "image/tiff", SourceInputDescriptor.binary()));
            assertEquals(ExtractionFailure.WRITE_LIMIT, error.failure());
            assertEquals(0, paddleCalls.get());
        }
    }

    @Test
    void aTiffWhoseLaterFrameIsOversizedIsRefusedBeforeItIsSent() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        try (var extractor = withPaddle()) {
            // 12001 x 12000 is just over the 144 million pixel bound; one bit a pixel keeps it cheap to build.
            var large = new BufferedImage(12_001, 12_000, BufferedImage.TYPE_BYTE_BINARY);
            byte[] tiff = tiff(frame(40, 30), large);
            assertTrue(tiff.length < 20_971_520, "refused for its pixels, not its bytes");
            var error = assertThrows(ExtractionException.class,
                    () -> extractor.extract(tiff, "scan.tiff", "image/tiff", SourceInputDescriptor.binary()));
            assertEquals(ExtractionFailure.WRITE_LIMIT, error.failure());
            assertEquals(0, paddleCalls.get());
        }
    }

    @Test
    void aTiffWithinThePageAndPixelLimitsIsSent() throws Exception {
        serve(200, fixture("financial-statement-page.json"));
        try (var extractor = withPaddle(2)) {
            for (int frames = 1; frames <= 2; frames++) {
                var images = new BufferedImage[frames];
                Arrays.fill(images, frame(40, 30));
                byte[] tiff = tiff(images);
                var result = extractor.extract(tiff, "scan.tiff", "image/tiff", SourceInputDescriptor.binary());
                assertEquals("paddleocr-vl", result.metadata().get("parser"));
                assertEquals(frames, paddleCalls.get());
            }
        }
    }

    @Test
    void anEmptyEndpointLeavesTheProviderAbsent() {
        var source = new MapConfigurationPropertySource(Map.of(
                "memoryos.extraction.paddleocr-vl.endpoint", "",
                "memoryos.extraction.paddleocr-vl.revision", ""));
        var bound = new Binder(source)
                .bindOrCreate("memoryos.extraction.paddleocr-vl",
                        Bindable.of(PaddleOcrVlProperties.class));
        assertFalse(bound.configured());
        assertEquals(PaddleOcrVlProperties.DEFAULT_REVISION, bound.revision());
        assertEquals(Duration.ofMinutes(30), bound.timeout());
        assertEquals(2, bound.maxConcurrentRequests());

        var configured = new Binder(
                new MapConfigurationPropertySource(Map.of(
                        "memoryos.extraction.paddleocr-vl.endpoint", "http://172.24.244.79:8080",
                        "memoryos.extraction.paddleocr-vl.timeout", "15m",
                        "memoryos.extraction.paddleocr-vl.max-concurrent-requests", "3")))
                .bind("memoryos.extraction.paddleocr-vl",
                        Bindable.of(PaddleOcrVlProperties.class)).get();
        assertTrue(configured.configured());
        assertEquals(Duration.ofMinutes(15), configured.timeout());
        assertEquals(3, configured.maxConcurrentRequests());
        assertFalse(configured.parserConfiguration(1).contains("172.24.244.79"), "the endpoint is not the parser's identity");
        for (var invalid : List.of("ftp://ocr:1", "http://user@ocr:1", "http://ocr:1/?q=1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PaddleOcrVlProperties(URI.create(invalid), null, 0, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> new PaddleOcrVlProperties(null, null, 201, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PaddleOcrVlProperties(null, Duration.ZERO, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PaddleOcrVlProperties(null, null, 0, "a;b", null));
        assertThrows(IllegalArgumentException.class, () -> new PaddleOcrVlProperties(null, null, 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new PaddleOcrVlProperties(null, null, 0, null, 17));
        assertEquals(16, new PaddleOcrVlProperties(null, null, 0, null, 16).maxConcurrentRequests());
    }

    private DoclingSourceContentExtractor withPaddle() {
        return withPaddle(200);
    }

    private DoclingSourceContentExtractor withPaddle(int maxPages) {
        var paddle = new PaddleOcrVlExtractor(new PaddleOcrVlProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(10), maxPages, null, null), mapper);
        return new DoclingSourceContentExtractor(properties(), mapper, docling, paddle);
    }

    private static DoclingProperties properties() {
        return new DoclingProperties(null, null, null, 0, null, null, false, null);
    }

    private DocumentContent extract(DoclingSourceContentExtractor extractor, byte[] bytes, String name) throws ExtractionException {
        return extractor.extract(new ByteArrayInputStream(bytes), bytes.length, name, SourceInputDescriptor.binary());
    }

    private void doclingAnswers() {
        when(docling.convertDocument(any())).thenReturn(doclingDocument());
    }

    private BoundedDoclingClient.CanonicalResponse doclingDocument() {
        var document = mapper.readTree("""
                {"body":{"children":[{"$ref":"#/texts/0"}]},
                 "texts":[{"label":"text","text":"Doanh thu","prov":[]}],"pages":{}}
                """);
        return new BoundedDoclingClient.CanonicalResponse(mapper.createObjectNode().set("json_content", document),
                List.of(), "success", ResponseType.IN_BODY);
    }

    private JsonNode submittedOptions() {
        var request = ArgumentCaptor.forClass(ConvertDocumentRequest.class);
        verify(docling, atLeastOnce()).convertDocument(request.capture());
        return mapper.<JsonNode>valueToTree(request.getValue()).path("options");
    }

    private void serve(int status, String answer) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/layout-parsing", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                paddleCalls.incrementAndGet();
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    private String fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/paddleocr-vl/" + name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String blankAnswer() {
        return """
                {"logId":"blank","errorCode":0,"errorMsg":"Success","result":{"layoutParsingResults":[
                 {"prunedResult":{"width":1685,"height":1191,"parsing_res_list":[
                  {"block_label":"image","block_content":"","block_bbox":[10,10,500,500]},
                  {"block_label":"number","block_content":"7","block_bbox":[1522,1147,1538,1161]}]}}]}}
                """;
    }

    /** Landscape A4 pages with nothing on them but an image would carry: the Tasco scan shape. */
    private static byte[] scan(int pages) throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) pdf.addPage(new PDPage(new PDRectangle(842.04f, 595.44f)));
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] textPdf(int pages) throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int page = 0; page < pages; page++) {
                var target = new PDPage();
                pdf.addPage(target);
                try (var content = new PDPageContentStream(pdf, target)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(40, 740);
                    for (int line = 0; line < 4; line++) {
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

    /** A text-rich page followed by a page with no text layer. */
    private static byte[] textThenScan() throws Exception {
        try (var pdf = Loader.loadPDF(textPdf(1)); var out = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage(new PDRectangle(842.04f, 595.44f)));
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage frame(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] tiff(BufferedImage... frames) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        var out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionType("Deflate");
            writer.prepareWriteSequence(null);
            for (var frame : frames) writer.writeToSequence(new IIOImage(frame, null, null), parameters);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
