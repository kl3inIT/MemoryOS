package io.memoryos.ingestion.extraction;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Page;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads scans and images with PaddleOCR-VL. Its failures stand: there is no fallback to Docling or
 * Tesseract, whose misread amounts would be indexed as if they were right (MEM-192).
 */
final class PaddleOcrVlExtractor implements AutoCloseable {
    static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/tiff", "image/webp", "image/bmp");
    /** The Chat image bound, which the GPU node's layout service must also be able to decode. */
    private static final long MAX_IMAGE_BYTES = 20_971_520;
    private static final long MAX_IMAGE_PIXELS = 144_000_000;
    private final PaddleOcrVlProperties properties;
    private final PaddleOcrVlClient client;
    private final ObjectMapper mapper;

    PaddleOcrVlExtractor(PaddleOcrVlProperties properties, ObjectMapper mapper) {
        this(properties, mapper, new PaddleOcrVlClient(properties, mapper));
    }

    PaddleOcrVlExtractor(PaddleOcrVlProperties properties, ObjectMapper mapper, PaddleOcrVlClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
    }

    /**
     * @param layout the PDF's pages; null for an image
     * @return the document, or null when the layout holds no text at all (a photograph)
     */
    @Nullable DocumentContent read(PaddleOcrVlClient.Input input, @Nullable PdfLayout layout, String filename,
            String mediaType, long maxInput) throws ExtractionException {
        if (layout == null) admitImage(input, properties.maxPages());
        else if (layout.pages() < 1) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        else if (layout.pages() > properties.maxPages()) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
        var results = client.parse(input, layout == null ? PaddleOcrVlClient.FileType.IMAGE : PaddleOcrVlClient.FileType.PDF);
        List<Page> pages = layout == null ? List.of() : layout.sizes();
        var blocks = PaddleOcrVlDocument.blocks(results, layout == null ? List.of() : layout.frames());
        if (DocumentAssembly.semanticText(blocks).isBlank()) return null;
        return DocumentAssembly.publish(mapper, blocks, pages, null, mediaType, filename,
                Map.of("parser", "paddleocr-vl", "parser_configuration", properties.parserConfiguration(maxInput)),
                "paddleocr_vl");
    }

    /** A blank result is malformed, as it is for Docling: an index entry that answers nothing is not a success. */
    DocumentContent extract(PaddleOcrVlClient.Input input, @Nullable PdfLayout layout, String filename,
            String mediaType, long maxInput) throws ExtractionException {
        var content = read(input, layout, filename, mediaType, maxInput);
        if (content == null) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        return content;
    }

    /**
     * Size and dimensions from the headers alone; a small file must not decode to billions of pixels
     * there. PaddleX reads every frame of a multi-page TIFF as a page, so each frame is held to the same
     * pixel limit and the frame count to the page limit a PDF has.
     */
    private static void admitImage(PaddleOcrVlClient.Input input, int maxPages) throws ExtractionException {
        try {
            if (input.size() < 1 || input.size() > MAX_IMAGE_BYTES) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
            try (var stream = input.open(); var image = ImageIO.createImageInputStream(stream)) {
                var readers = image == null ? null : ImageIO.getImageReaders(image);
                if (readers == null || !readers.hasNext()) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
                var reader = readers.next();
                try {
                    reader.setInput(image, false, true);
                    int frames = reader.getNumImages(true);
                    if (frames < 1) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
                    if (frames > maxPages) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
                    for (int frame = 0; frame < frames; frame++) {
                        long pixels = (long) reader.getWidth(frame) * reader.getHeight(frame);
                        if (pixels < 1 || pixels > MAX_IMAGE_PIXELS) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        }
    }

    @Override public void close() {
        client.close();
    }
}
