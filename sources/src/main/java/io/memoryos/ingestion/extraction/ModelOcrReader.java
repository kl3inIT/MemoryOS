package io.memoryos.ingestion.extraction;

import io.memoryos.ai.AiException;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads scans and images with the catalog model of one OCR connection: each page is rendered as a JPEG and read in a
 * call of its own, a few pages at a time, and a page's text becomes one block on that page. A model returns text, not
 * a layout, so a table arrives as Markdown inside its page's block and no block carries a position.
 */
final class ModelOcrReader implements OcrReader {
    private static final Logger LOG = LoggerFactory.getLogger(ModelOcrReader.class);
    static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/webp");
    /** The page limit a scan has with every reader. */
    static final int MAX_PAGES = 200;
    /** Enough for body text at a readable size; a provider downsizes a larger picture anyway. */
    private static final float DPI = 150;
    /** A picture over this is rendered again at a lower resolution; providers refuse or resize beyond a few megabytes. */
    private static final int MAX_PICTURE_BYTES = 4_194_304;
    /** The Chat image bound, which an image sent as it is must keep. */
    private static final long MAX_IMAGE_BYTES = 20_971_520;
    /** Pages one document has at the provider at once. */
    private static final int CONCURRENT_PAGES = 4;

    private final OcrPageReader pages;
    private final OcrConnectionService.Connection connection;
    private final ObjectMapper mapper;

    ModelOcrReader(OcrPageReader pages, OcrConnectionService.Connection connection, ObjectMapper mapper) {
        this.pages = pages;
        this.connection = connection;
        this.mapper = mapper;
    }

    @Override public boolean readsImage(String mediaType) { return IMAGES.contains(mediaType); }

    @Override public @Nullable DocumentContent read(PaddleOcrVlClient.Input input, @Nullable PdfLayout layout,
            String filename, String mediaType, long maxInput) throws ExtractionException {
        List<String> texts = layout == null ? List.of(image(input, mediaType)) : pdf(input, layout);
        var blocks = new ArrayList<Block>();
        for (int page = 0; page < texts.size(); page++) {
            if (texts.get(page).isBlank()) continue;
            blocks.add(new Block(blocks.size(), Kind.PARAGRAPH, texts.get(page), null,
                    List.of(Location.page(page + 1, null)), null, null, null));
        }
        if (blocks.isEmpty()) return null;
        return DocumentAssembly.publish(mapper, blocks, layout == null ? List.of() : layout.sizes(), null, mediaType,
                filename, Map.of("parser", "model-ocr", "parser_configuration", parserConfiguration(maxInput)),
                "model_ocr");
    }

    private String image(PaddleOcrVlClient.Input input, String mediaType) throws ExtractionException {
        try {
            if (input.size() < 1 || input.size() > MAX_IMAGE_BYTES) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
            try (var stream = input.open()) {
                return page(stream.readAllBytes(), mediaType);
            }
        } catch (IOException unreadable) {
            throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        }
    }

    /** Pages are rendered one after another, since a PDFBox document is not shared between threads, and read together. */
    private List<String> pdf(PaddleOcrVlClient.Input input, PdfLayout layout) throws ExtractionException {
        if (layout.pages() < 1) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        if (layout.pages() > MAX_PAGES) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
        var permits = new Semaphore(CONCURRENT_PAGES);
        var readings = new ArrayList<Future<String>>(layout.pages());
        try (var stream = input.open(); var document = Loader.loadPDF(new RandomAccessReadBuffer(stream));
                var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var renderer = new PDFRenderer(document);
            try {
                for (int page = 0; page < layout.pages(); page++) {
                    permits.acquire();
                    byte[] picture;
                    try {
                        picture = picture(renderer, page);
                    } catch (IOException unreadable) {
                        permits.release();
                        throw unreadable;
                    } catch (RuntimeException corrupt) {
                        // PDFBox reports a damaged page image this way; the document is malformed, not the reader.
                        permits.release();
                        throw new IOException(corrupt);
                    }
                    readings.add(readers.submit(() -> {
                        try {
                            return page(picture, "image/jpeg");
                        } finally {
                            permits.release();
                        }
                    }));
                }
                var texts = new ArrayList<String>(readings.size());
                for (var reading : readings) texts.add(reading.get());
                return texts;
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof ExtractionException extraction) throw extraction;
                throw DocumentAssembly.failure(ExtractionFailure.INTERNAL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw DocumentAssembly.failure(ExtractionFailure.TIMEOUT);
            } finally {
                // A failed page ends the document; the pages still being read are not waited for.
                readings.forEach(reading -> reading.cancel(true));
            }
        } catch (IOException unreadable) {
            throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        }
    }

    private static byte[] picture(PDFRenderer renderer, int page) throws IOException {
        byte[] picture = jpeg(renderer.renderImageWithDPI(page, DPI, ImageType.RGB));
        return picture.length <= MAX_PICTURE_BYTES ? picture : jpeg(renderer.renderImageWithDPI(page, DPI / 2, ImageType.RGB));
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream(262_144);
        if (!ImageIO.write(image, "jpeg", bytes)) throw new IOException("No JPEG writer");
        return bytes.toByteArray();
    }

    /**
     * One page's text. A model that is gone or no longer reads images is the connection's configuration; any other
     * failure is the provider's, which did not answer, so the document may be read again once it does.
     */
    private String page(byte[] picture, String mediaType) throws ExtractionException {
        try {
            return pages.read(connection, picture, mediaType);
        } catch (AiException configuration) {
            LOG.atWarn().addKeyValue("event", "model_ocr.page.refused").addKeyValue("error_code", configuration.code())
                    .log("The OCR connection's model cannot read a page");
            throw DocumentAssembly.failure(ExtractionFailure.INTERNAL);
        } catch (RuntimeException provider) {
            LOG.atWarn().addKeyValue("event", "model_ocr.page.failed")
                    .addKeyValue("error_type", provider.getClass().getName()).log("The OCR connection's model did not read a page");
            throw DocumentAssembly.failure(ExtractionFailure.CONNECTION_FAILED);
        }
    }

    private String parserConfiguration(long maxInput) {
        return "memoryos-extraction-v2;model-ocr=" + connection.modelConfigurationId() + ";dpi=" + (int) DPI
                + ";maxPages=" + MAX_PAGES + ";maxInput=" + maxInput + ";maxOutput=33554432";
    }

    @Override public void close() {}
}
