package io.memoryos.voice;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * One observation per voice provider call, {@code memoryos.chat.voice.request} with bounded {@code provider},
 * {@code operation} and {@code outcome} keys; its timer is what the Chat &amp; AI dashboard reads.
 */
final class VoiceObservations {
    private VoiceObservations() {}

    static Observation start(ObservationRegistry observations, VoiceProvider provider, String operation) {
        return Observation.createNotStarted("memoryos.chat.voice.request", observations)
                .lowCardinalityKeyValue("provider", provider.name()).lowCardinalityKeyValue("operation", operation).start();
    }

    /** The exception type only: provider failures can carry account details. */
    static void error(Observation observation, Throwable failure) {
        observation.error(new IllegalStateException(failure.getClass().getSimpleName()));
    }

    static void stop(Observation observation, String outcome) {
        observation.lowCardinalityKeyValue("outcome", outcome).stop();
    }
}
