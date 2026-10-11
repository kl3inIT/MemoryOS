package io.memoryos.ingestion.extraction;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Reads scans and images with something that reads one page picture at a time, a catalog model or an OCR service: each
 * page is rendered as a JPEG and read in a call of its own, a few pages at a time, and a page's text becomes one block
 * on that page. Such a reader returns text, not a layout, so a table arrives as Markdown inside its page's block and no
 * block carries a position.
 */
final class PagePictureOcrReader implements OcrReader {
    /** The text of one page picture; empty when it holds none. */
    @FunctionalInterface
    interface PageText {
        String read(byte[] picture, String mediaType) throws ExtractionException;
    }

    /**
     * @param parser the reader's name in a document's metadata, such as {@code model-ocr}
     * @param identity what tells one configuration of the reader from another in {@code parser_configuration}
     * @param images image types sent as they are; another image is sent as a JPEG
     * @param maxPictureBytes the largest picture the reader takes
     */
    record Settings(String parser, String identity, Set<String> images, int maxPictureBytes) {}

    /** The page limit a scan has with every reader. */
    static final int MAX_PAGES = 200;
    /** Enough for body text at a readable size; a provider downsizes a larger picture anyway. */
    private static final float DPI = 150;
    /** The Chat image bound. */
    private static final long MAX_IMAGE_BYTES = 20_971_520;
    /** Pages one document has at the reader at once. */
    private static final int CONCURRENT_PAGES = 4;
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/webp");

    private final PageText page;
    private final Settings settings;
    private final ObjectMapper mapper;

    PagePictureOcrReader(PageText page, Settings settings, ObjectMapper mapper) {
        this.page = page;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override public boolean readsImage(String mediaType) { return IMAGES.contains(mediaType); }

    @Override public @Nullable DocumentContent read(PaddleOcrVlClient.Input input, @Nullable PdfLayout layout,
            String filename, String mediaType, long maxInput) throws ExtractionException {
        List<String> texts = layout == null ? List.of(image(input, mediaType)) : pdf(input, layout);
        var blocks = new ArrayList<Block>();
        for (int index = 0; index < texts.size(); index++) {
            if (texts.get(index).isBlank()) continue;
            blocks.add(new Block(blocks.size(), Kind.PARAGRAPH, texts.get(index), null,
                    List.of(Location.page(index + 1, null)), null, null, null));
        }
        if (blocks.isEmpty()) return null;
        return DocumentAssembly.publish(mapper, blocks, layout == null ? List.of() : layout.sizes(), null, mediaType,
                filename, Map.of("parser", settings.parser(), "parser_configuration", parserConfiguration(maxInput)),
                settings.parser().replace('-', '_'));
    }

    /** An image the reader takes is sent as it is; any other, or one too large, is sent as a JPEG. */
    private String image(PaddleOcrVlClient.Input input, String mediaType) throws ExtractionException {
        try {
            if (input.size() < 1 || input.size() > MAX_IMAGE_BYTES) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
            byte[] bytes;
            try (var stream = input.open()) {
                bytes = stream.readAllBytes();
            }
            if (settings.images().contains(mediaType) && bytes.length <= settings.maxPictureBytes())
                return page.read(bytes, mediaType);
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
            var opaque = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
            var canvas = opaque.createGraphics();
            try {
                // JPEG has no transparency; a transparent picture is read on white, as it is seen.
                canvas.setColor(Color.WHITE);
                canvas.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
                canvas.drawImage(decoded, 0, 0, null);
            } finally {
                canvas.dispose();
            }
            byte[] picture = jpeg(opaque);
            if (picture.length > settings.maxPictureBytes()) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
            return page.read(picture, "image/jpeg");
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
                for (int index = 0; index < layout.pages(); index++) {
                    permits.acquire();
                    byte[] picture;
                    try {
                        picture = picture(renderer, index);
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
                            // A page still too large at half the resolution is kept as read with no text.
                            return picture.length > settings.maxPictureBytes() ? "" : page.read(picture, "image/jpeg");
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

    /** A picture over the reader's limit is rendered again at half the resolution. */
    private byte[] picture(PDFRenderer renderer, int index) throws IOException {
        byte[] picture = jpeg(renderer.renderImageWithDPI(index, DPI, ImageType.RGB));
        return picture.length <= settings.maxPictureBytes() ? picture
                : jpeg(renderer.renderImageWithDPI(index, DPI / 2, ImageType.RGB));
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream(262_144);
        if (!ImageIO.write(image, "jpeg", bytes)) throw new IOException("No JPEG writer");
        return bytes.toByteArray();
    }

    private String parserConfiguration(long maxInput) {
        return "memoryos-extraction-v2;" + settings.parser() + "=" + settings.identity() + ";dpi=" + (int) DPI
                + ";maxPages=" + MAX_PAGES + ";maxInput=" + maxInput + ";maxOutput=33554432";
    }

    @Override public void close() {}
}
