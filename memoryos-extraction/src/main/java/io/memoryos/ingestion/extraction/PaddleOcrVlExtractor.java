package io.memoryos.ingestion.extraction;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Line;
import io.memoryos.document.ExtractedDocument.Page;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Reads scans and images with the PaddleOCR-VL service of one OCR connection. */
final class PaddleOcrVlExtractor implements OcrReader {
    private static final Logger LOG = LoggerFactory.getLogger(PaddleOcrVlExtractor.class);
    static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/tiff", "image/webp", "image/bmp");
    /** The Chat image bound, which the GPU node's layout service must also be able to decode. */
    private static final long MAX_IMAGE_BYTES = 20_971_520;
    private static final long MAX_IMAGE_PIXELS = 144_000_000;
    private final PaddleOcrVlProperties properties;
    private final PaddleOcrVlClient client;
    private final ObjectMapper mapper;

    PaddleOcrVlExtractor(PaddleOcrVlProperties properties, URI service, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = new PaddleOcrVlClient(properties, service, mapper);
    }

    @Override public boolean readsImage(String mediaType) { return IMAGES.contains(mediaType); }

    @Override public @Nullable DocumentContent read(PaddleOcrVlClient.Input input, @Nullable PdfLayout layout, String filename,
            String mediaType, long maxInput, boolean lines) throws ExtractionException {
        if (layout == null) admitImage(input, properties.maxPages());
        else if (layout.pages() < 1) throw DocumentAssembly.failure(ExtractionFailure.MALFORMED);
        else if (layout.pages() > properties.maxPages()) throw DocumentAssembly.failure(ExtractionFailure.WRITE_LIMIT);
        var type = layout == null ? PaddleOcrVlClient.FileType.IMAGE : PaddleOcrVlClient.FileType.PDF;
        var results = client.parse(input, type, PaddleOcrVlClient.Task.LAYOUT);
        List<Page> pages = layout == null ? List.of() : layout.sizes();
        List<PdfLayout.Frame> frames = layout == null ? List.of() : layout.frames();
        var blocks = PaddleOcrVlDocument.blocks(results, frames);
        if (DocumentAssembly.semanticText(blocks).isBlank()) return null;
        List<Line> located = lines ? lines(input, type, frames, blocks) : List.of();
        return DocumentAssembly.publish(mapper, blocks, pages, located, null, mediaType, filename,
                Map.of("parser", "paddleocr-vl", "parser_configuration", properties.parserConfiguration(maxInput, lines)),
                "paddleocr_vl");
    }

    /**
     * The text lines of the input, from a second request to the same service. They only narrow what Search's
     * reader marks inside blocks already read, so an answer that fails or is malformed leaves the document without
     * lines (the client logs {@code paddleocr_vl.lines.failed}); a Worker that is stopping still stops.
     */
    private List<Line> lines(PaddleOcrVlClient.Input input, PaddleOcrVlClient.FileType type,
            List<PdfLayout.Frame> frames, List<Block> blocks) throws ExtractionException {
        List<Line> located;
        try {
            located = PaddleOcrVlDocument.lines(client.parse(input, type, PaddleOcrVlClient.Task.LINES), frames, blocks);
        } catch (ExtractionException failed) {
            if (Thread.currentThread().isInterrupted()) throw failed;
            return List.of();
        }
        LOG.atInfo().addKeyValue("event", "paddleocr_vl.lines.located").addKeyValue("line_count", located.size())
                .log("PaddleOCR-VL text lines given to their blocks");
        return located;
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
