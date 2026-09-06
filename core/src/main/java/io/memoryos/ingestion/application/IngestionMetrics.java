package io.memoryos.ingestion.application;

import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationWorkload;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class IngestionMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionMetrics.class);
    private enum Outcome { COMPLETED, SKIPPED, FAILED, UNHANDLED }

    private final Map<OperationWorkload, Map<Outcome, Counter>> outcomes = new EnumMap<>(OperationWorkload.class);
    private final Map<OperationWorkload, Timer> initialQueueWait = new EnumMap<>(OperationWorkload.class);

    IngestionMetrics(MeterRegistry registry) {
        for (var workload : OperationWorkload.values()) {
            var counters = new EnumMap<Outcome, Counter>(Outcome.class);
            for (var outcome : Outcome.values()) {
                counters.put(outcome, Counter.builder("memoryos.operation.outcomes")
                        .description("Processing invocations by business result, independent of transport ACK")
                        .tag("workload", workload.name()).tag("outcome", outcome.name()).register(registry));
            }
            outcomes.put(workload, counters);
            initialQueueWait.put(workload, Timer.builder("memoryos.operation.initial.queue.wait")
                    .description("Database creation to first successful claim; excludes retry and reclaim waits")
                    .tag("workload", workload.name())
                    .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5),
                            Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofMinutes(1),
                            Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1))
                    .register(registry));
        }
    }

    void completed(OperationWorkload workload, IngestionCoordinator.Outcome outcome) {
        record(() -> outcomes.get(workload).get(Outcome.valueOf(outcome.name())).increment());
    }

    void unhandled(OperationWorkload workload) {
        record(() -> outcomes.get(workload).get(Outcome.UNHANDLED).increment());
    }

    void firstClaim(OperationWorkload workload, @Nullable Duration wait) {
        if (wait != null) {
            record(() -> initialQueueWait.get(workload).record(wait.isNegative() ? Duration.ZERO : wait));
        }
    }

    private static void record(Runnable measurement) {
        try {
            measurement.run();
        } catch (RuntimeException exception) {
            LOGGER.atWarn().addKeyValue("event", "ingestion.metrics.recording_failed")
                    .addKeyValue("error_type", exception.getClass().getName())
                    .log("Could not record ingestion measurement");
        }
    }
}
