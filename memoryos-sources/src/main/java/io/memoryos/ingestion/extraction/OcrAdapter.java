package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrProvider;

/** One OCR service's protocol. {@link OcrAdapterRegistry} holds exactly one per {@link OcrProvider}. */
interface OcrAdapter {
    OcrProvider provider();

    /**
     * The reader of one connection, kept while the connection is unchanged and closed by its owner.
     *
     * @param key the connection's plaintext key, empty when it has none
     */
    OcrReader reader(OcrConnectionService.Connection connection, String key);
}
