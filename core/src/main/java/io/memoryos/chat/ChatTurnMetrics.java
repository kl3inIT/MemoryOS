package io.memoryos.chat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * One sample per finished Chat turn and per guardrail check, for the Chat &amp; AI dashboard. Every tag is a bounded
 * value (a status, a typed failure code, a refusal reason); never a model, a person or any content.
 */
public final class ChatTurnMetrics {
    private final MeterRegistry meters;

    public ChatTurnMetrics(MeterRegistry meters) { this.meters = meters; }

    /** Admission to the terminal outcome. */
    void turn(ChatMessage.Status status, @Nullable String failure, @Nullable String refusal, boolean grounded, boolean research,
              long nanos) {
        Timer.builder("memoryos.chat.turn").description("A Chat turn from admission to its terminal outcome")
                .tag("status", status.name().toLowerCase(Locale.ROOT))
                .tag("failure", failure == null ? "none" : failure)
                .tag("refusal", refusal == null ? "none" : refusal)
                .tag("grounded", Boolean.toString(grounded))
                .tag("research", Boolean.toString(research))
                .register(meters).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Admission to the first text the person sees, a refusal included. */
    void firstText(boolean grounded, long nanos) {
        Timer.builder("memoryos.chat.turn.first.text").description("A Chat turn from admission to its first visible text")
                .tag("grounded", Boolean.toString(grounded))
                .register(meters).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** The MEM-195 check before the answer model; {@code unavailable} when it could not decide. */
    void guardrail(String kind, long nanos) {
        Timer.builder("memoryos.chat.guardrail.check").description("The grounding and sensitive-topic check of a Chat turn")
                .tag("kind", kind)
                .register(meters).record(nanos, TimeUnit.NANOSECONDS);
    }
}
