package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.ai.ocr.OcrServiceUsage;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Exactly one adapter per {@link OcrProvider}; a missing or second one fails startup. */
final class OcrAdapterRegistry {
    private final Map<OcrProvider, OcrAdapter> adapters = new EnumMap<>(OcrProvider.class);

    /** The adapters this build installs, calling their services with these settings. */
    /** A page picture a cloud service reads answers within this, or the page has failed. */
    private static final Duration PAGE_TIMEOUT = Duration.ofMinutes(1);

    static OcrAdapterRegistry installed(PaddleOcrVlProperties paddle, OcrPageReader pages, OcrServiceUsage usage,
            ObjectMapper mapper) {
        return new OcrAdapterRegistry(List.of(new PaddleOcrVlOcrAdapter(paddle, mapper), new ModelOcrAdapter(pages, mapper),
                new AiHayOcrAdapter(usage, PAGE_TIMEOUT, mapper)));
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
