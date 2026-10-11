package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrProvider;
import java.net.URI;
import tools.jackson.databind.ObjectMapper;

/** PaddleOCR-VL's layout-parsing service: the whole PDF or image in, layout blocks with tables and positions out. */
final class PaddleOcrVlOcrAdapter implements OcrAdapter {
    private final PaddleOcrVlProperties properties;
    private final ObjectMapper mapper;

    PaddleOcrVlOcrAdapter(PaddleOcrVlProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public OcrProvider provider() { return OcrProvider.PADDLEOCR_VL; }

    /** The service takes no key; it is reached by network only. */
    @Override public OcrReader reader(OcrConnectionService.Connection connection, String key) {
        return new PaddleOcrVlExtractor(properties, URI.create(connection.endpoint()), mapper);
    }
}
