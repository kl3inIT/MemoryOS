package io.memoryos.voice;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;

/** The voice registry as the application wires it, and observations recorded as timers in a test's meters. */
final class VoiceAdapters {
    private VoiceAdapters() {}

    static List<VoiceAdapter> all() {
        return List.of(new OpenAiVoiceAdapter(), new OpenAiCompatibleVoiceAdapter(), new ElevenLabsVoiceAdapter(),
                new AzureVoiceAdapter(), new SonioxVoiceAdapter());
    }

    static VoiceAdapterRegistry registry() { return new VoiceAdapterRegistry(all()); }

    static ObservationRegistry observations(MeterRegistry meters) {
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return observations;
    }
}
