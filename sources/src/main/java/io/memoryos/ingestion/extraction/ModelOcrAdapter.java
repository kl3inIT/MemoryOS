package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.ai.ocr.OcrProvider;
import tools.jackson.databind.ObjectMapper;

/** A catalog model that reads images: one page picture in, the page's text out. */
final class ModelOcrAdapter implements OcrAdapter {
    private final OcrPageReader pages;
    private final ObjectMapper mapper;

    ModelOcrAdapter(OcrPageReader pages, ObjectMapper mapper) {
        this.pages = pages;
        this.mapper = mapper;
    }

    @Override public OcrProvider provider() { return OcrProvider.MODEL; }

    /** The model's own provider holds the address and the key; the connection only names the model. */
    @Override public OcrReader reader(OcrConnectionService.Connection connection, String key) {
        return new ModelOcrReader(pages, connection, mapper);
    }
}
