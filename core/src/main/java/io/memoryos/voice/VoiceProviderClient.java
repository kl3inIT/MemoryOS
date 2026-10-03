package io.memoryos.voice;

import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** Voice connection checks. Provider payloads and credentials never reach responses, logs or metrics. */
@Component
public class VoiceProviderClient {
    private final Semaphore checks = new Semaphore(2);
    private final VoiceAdapterRegistry adapters;
    private final ObservationRegistry observations;

    public VoiceProviderClient(VoiceAdapterRegistry adapters, ObservationRegistry observations) {
        this.adapters = adapters;
        this.observations = observations;
    }

    /**
     * Onyx validate_credentials parity: an authorized listing proves the endpoint and credential (OpenAI-protocol and
     * ElevenLabs models, Azure voices, Soniox transcriptions). It does not certify that the configured models or voices
     * exist. One observation, {@code memoryos.chat.voice.request} with operation {@code verify}; checks are not a
     * billing estimate.
     */
    public void verify(VoiceConnectionService.Probe probe) {
        if (!checks.tryAcquire()) throw VoiceException.busy();
        var observation = VoiceObservations.start(observations, probe.provider(), "verify");
        String outcome = "failed";
        try (var _ = observation.openScope()) {
            adapters.adapter(probe.provider()).verify(probe);
            outcome = "succeeded";
        } catch (RuntimeException failure) {
            VoiceObservations.error(observation, failure);
            throw failure;
        } finally {
            checks.release();
            VoiceObservations.stop(observation, outcome);
        }
    }
}
