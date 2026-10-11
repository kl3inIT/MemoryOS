package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrProvider;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Exactly one adapter per {@link OcrProvider}; a missing or second one fails startup. */
final class OcrAdapterRegistry {
    private final Map<OcrProvider, OcrAdapter> adapters = new EnumMap<>(OcrProvider.class);

    /** The adapters this build installs, calling their services with these settings. */
    static OcrAdapterRegistry installed(PaddleOcrVlProperties paddle, ObjectMapper mapper) {
        return new OcrAdapterRegistry(List.of(new PaddleOcrVlOcrAdapter(paddle, mapper)));
    }

    OcrAdapterRegistry(Collection<? extends OcrAdapter> installed) {
        for (var adapter : installed) {
            if (adapters.putIfAbsent(adapter.provider(), adapter) != null)
                throw new IllegalStateException("Two OCR adapters for " + adapter.provider());
        }
        for (var provider : OcrProvider.values()) {
            if (!adapters.containsKey(provider)) throw new IllegalStateException("No OCR adapter for " + provider);
        }
    }

    OcrAdapter adapter(OcrProvider provider) { return adapters.get(provider); }
}
