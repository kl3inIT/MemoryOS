package io.memoryos.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Bounded stage names only: questions, document metadata and identities are never metric tags. */
@Component
public class SearchTimings {
    public enum Stage { PREFETCH, SEMANTIC_REWRITE, KEYWORD_REWRITE, SOURCE_FILTER, TIME_FILTER, EMBEDDING, HYBRID, AUTHORIZATION, FUSION, SELECTION, EXPANSION, CLASSIFICATION }
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public SearchTimings(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    public <T> T measure(Stage stage, Supplier<T> action) {
        String name = stage.name().toLowerCase(Locale.ROOT);
        var observation = Observation.createNotStarted("memoryos.search.stage", observations).lowCardinalityKeyValue("stage", name).start();
        long started = System.nanoTime();
        String outcome = "success";
        try (var _ = observation.openScope()) {
            return action.get();
        } catch (RuntimeException | Error failure) {
            outcome = "failed";
            observation.error(new IllegalStateException(failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            observation.stop();
            meters.timer("memoryos.search.stage.duration", "stage", name, "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
