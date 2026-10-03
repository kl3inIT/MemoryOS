package io.memoryos.ingestion.extraction;

import io.memoryos.document.ExtractionFailure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Counts each Docling failure that reaches the native fallback, by the failure and what the native
 * reader made of it. A fallback publishes a Document without tables or boxes while the Source reports
 * success, so this counter is what makes a broken Docling visible. Every series is registered at zero,
 * so the first fallback is an increase rather than a series appearing from nothing.
 */
final class DoclingFallbackMetrics {
    private static final String NAME = "memoryos.extraction.docling.fallback";

    /** What the native reader made of a document Docling could not convert. */
    enum Outcome {
        /** The native text stood in and the Document is published from it. */
        READ,
        /** The native text was too thin to stand in; Docling's failure stands. */
        REFUSED,
        /** The native reader failed too; Docling's failure stands. */
        FAILED
    }

    private final Map<ExtractionFailure, Map<Outcome, Counter>> counters = new EnumMap<>(ExtractionFailure.class);

    DoclingFallbackMetrics(MeterRegistry registry, Set<ExtractionFailure> reasons) {
        for (ExtractionFailure reason : reasons) {
            var outcomes = new EnumMap<Outcome, Counter>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(outcome, Counter.builder(NAME)
                        .description("Docling failures that reached the native fallback, by reason and outcome")
                        .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .register(registry));
            }
            counters.put(reason, outcomes);
        }
    }

    void record(ExtractionFailure reason, Outcome outcome) {
        counters.get(reason).get(outcome).increment();
    }
}
