package io.memoryos.chat.research;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Deep research spans and metrics. Labels use only the bounded vocabularies below; questions, tasks, plans, reports and
 * document content never reach telemetry.
 */
public final class ResearchTelemetry {
    /** Onyx phase names; each is one span of {@code memoryos.chat.research.phase}. */
    public enum Phase { CLARIFICATION_STEP, RESEARCH_PLAN_STEP, RESEARCH_EXECUTION_STEP, RESEARCH_AGENT, GENERATE_INTERMEDIATE_REPORT, GENERATE_REPORT }

    /** Where a report was forced: the orchestrator or one research agent. */
    public enum Scope { ORCHESTRATOR, AGENT }

    /** Why a report was forced: the elapsed-time bound or the last cycle. */
    public enum Reason { TIME, CYCLES }

    /** How a research agent ended. */
    public enum AgentOutcome { COMPLETED, FAILED, TIMEOUT }

    public static final ResearchTelemetry NOOP = new ResearchTelemetry(new SimpleMeterRegistry(), ObservationRegistry.NOOP);

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public ResearchTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    /** The observation to parent agent spans on, which run on other threads than the orchestrator. */
    @Nullable Observation current() {
        return observations.getCurrentObservation();
    }

    /**
     * Runs one phase in its span. Timer {@code memoryos.chat.research.phase.duration}: monotonic time from phase start to
     * return or throw; tags {@code phase} (lower-case {@link Phase}) and {@code outcome} ({@code completed}, {@code failed},
     * {@code canceled}). A phase is never retried, so each run records once.
     */
    <T> T phase(Phase phase, @Nullable Observation parent, Supplier<T> action) {
        String name = name(phase);
        var observation = Observation.createNotStarted("memoryos.chat.research.phase", observations).lowCardinalityKeyValue("phase", name);
        // Without an explicit parent the observation already parents on the current one.
        if (parent != null) observation.parentObservation(parent);
        observation.start();
        long started = System.nanoTime();
        String outcome = "completed";
        try (var _ = observation.openScope()) {
            return action.get();
        } catch (CancellationException stopped) {
            outcome = "canceled";
            throw stopped;
        } catch (RuntimeException | Error failure) {
            outcome = "failed";
            // The exception type only: provider and tool failures can carry private content.
            observation.error(new IllegalStateException(failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
            meters.timer("memoryos.chat.research.phase.duration", "phase", name, "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    void phase(Phase phase, Runnable action) {
        phase(phase, null, () -> { action.run(); return null; });
    }

    /** Summary {@code memoryos.chat.research.cycles}: orchestrator inferences that ran in one research turn. */
    void cycles(int cycles) {
        meters.summary("memoryos.chat.research.cycles").record(cycles);
    }

    /** Counter {@code memoryos.chat.research.agents}; tag {@code outcome} (lower-case {@link AgentOutcome}). */
    void agent(AgentOutcome outcome) {
        meters.counter("memoryos.chat.research.agents", "outcome", name(outcome)).increment();
    }

    /** Counter {@code memoryos.chat.research.forced.reports}; tags {@code scope} and {@code reason} (lower-case enums). */
    void forcedReport(Scope scope, Reason reason) {
        meters.counter("memoryos.chat.research.forced.reports", "scope", name(scope), "reason", name(reason)).increment();
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
