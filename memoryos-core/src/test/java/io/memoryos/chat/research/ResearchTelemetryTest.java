package io.memoryos.chat.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ResearchTelemetryTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final ResearchTelemetry telemetry;

    ResearchTelemetryTest() {
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public void onStop(Observation.Context context) { stopped.add(context); }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        telemetry = new ResearchTelemetry(meters, observations);
    }

    @Test
    void phasesRecordBoundedOutcomesAndNeverTheFailureMessage() {
        assertEquals("plan", telemetry.phase(ResearchTelemetry.Phase.RESEARCH_PLAN_STEP, null, () -> "plan"));
        assertThrows(IllegalStateException.class, () -> telemetry.phase(ResearchTelemetry.Phase.RESEARCH_AGENT, null, () -> {
            throw new IllegalStateException("private task text");
        }));
        assertThrows(CancellationException.class, () -> telemetry.phase(ResearchTelemetry.Phase.GENERATE_REPORT, () -> {
            throw new CancellationException();
        }));

        assertEquals(1, meters.timer("memoryos.chat.research.phase.duration", "phase", "research_plan_step", "outcome", "completed").count());
        assertEquals(1, meters.timer("memoryos.chat.research.phase.duration", "phase", "research_agent", "outcome", "failed").count());
        assertEquals(1, meters.timer("memoryos.chat.research.phase.duration", "phase", "generate_report", "outcome", "canceled").count());
        assertEquals(3, stopped.size());
        var failed = stopped.get(1);
        assertEquals("IllegalStateException", failed.getError().getMessage());
        assertEquals(List.of("outcome", "phase"), StreamSupport.stream(failed.getAllKeyValues().spliterator(), false).map(KeyValue::getKey).sorted().toList());
        assertFalse(meters.getMeters().stream().anyMatch(meter -> meter.getId().getTags().toString().contains("private")));
    }

    @Test
    void agentSpansRunOnOtherThreadsUnderTheGivenParent() throws Exception {
        telemetry.phase(ResearchTelemetry.Phase.RESEARCH_EXECUTION_STEP, () -> {
            var parent = telemetry.current();
            var thread = Thread.ofVirtual().start(() -> telemetry.phase(ResearchTelemetry.Phase.RESEARCH_AGENT, parent, () -> telemetry.phase(ResearchTelemetry.Phase.GENERATE_INTERMEDIATE_REPORT, null, () -> "report")));
            try { thread.join(); } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
        });
        var report = stopped.get(0);
        var agent = stopped.get(1);
        var execution = stopped.get(2);
        assertSame(agent, report.getParentObservation().getContextView());
        assertSame(execution, agent.getParentObservation().getContextView());
    }

    @Test
    void countersUseOnlyTheirVocabularies() {
        telemetry.agent(ResearchTelemetry.AgentOutcome.TIMEOUT);
        telemetry.forcedReport(ResearchTelemetry.Scope.AGENT, ResearchTelemetry.Reason.CYCLES);
        telemetry.cycles(3);
        assertEquals(1, meters.counter("memoryos.chat.research.agents", "outcome", "timeout").count());
        assertEquals(1, meters.counter("memoryos.chat.research.forced.reports", "scope", "agent", "reason", "cycles").count());
        assertEquals(3, meters.summary("memoryos.chat.research.cycles").totalAmount());
    }
}
