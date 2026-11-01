package io.memoryos.voice;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Exactly one adapter per {@link VoiceProvider}, checked when the application starts: a provider without an adapter,
 * or with two, fails startup rather than a request. Which functions a provider offers follows from the interfaces its
 * adapter implements.
 */
@Component
public final class VoiceAdapterRegistry {
    private final Map<VoiceProvider, VoiceAdapter> adapters = new EnumMap<>(VoiceProvider.class);

    VoiceAdapterRegistry(List<VoiceAdapter> adapters) {
        for (var adapter : adapters)
            if (this.adapters.putIfAbsent(adapter.provider(), adapter) != null)
                throw new IllegalStateException("Two voice adapters for " + adapter.provider());
        for (var provider : VoiceProvider.values())
            if (!this.adapters.containsKey(provider)) throw new IllegalStateException("No voice adapter for " + provider);
    }

    public VoiceProviderCapabilities capabilities(VoiceProvider provider) { return adapter(provider).capabilities(); }
    /** Whether the provider can read text aloud; speech-to-text is common to every provider. */
    public boolean speaks(VoiceProvider provider) { return adapter(provider) instanceof SpeechSynthesisAdapter; }
    /** Whether the provider transcribes a whole uploaded file, as opposed to dictation only. */
    public boolean transcribesRecordings(VoiceProvider provider) { return adapter(provider) instanceof BatchTranscriptionAdapter; }
    /** Whether an uploaded recording's transcript separates speakers. */
    public boolean diarizesRecordings(VoiceProvider provider) { return batch(provider).map(BatchTranscriptionAdapter::diarizes).orElse(false); }
    /** The largest recording the provider accepts; bounded by what MemoryOS stores for providers without a limit. */
    public long maxRecordingBytes(VoiceProvider provider) {
        return batch(provider).map(BatchTranscriptionAdapter::maxBytes).orElse(500L * 1024 * 1024);
    }

    VoiceAdapter adapter(VoiceProvider provider) { return adapters.get(provider); }
    Optional<RealtimeTranscriptionAdapter> realtime(VoiceProvider provider) {
        return adapter(provider) instanceof RealtimeTranscriptionAdapter realtime ? Optional.of(realtime) : Optional.empty();
    }
    Optional<LiveTranscriptionAdapter> live(VoiceProvider provider) {
        return adapter(provider) instanceof LiveTranscriptionAdapter live ? Optional.of(live) : Optional.empty();
    }
    Optional<BatchTranscriptionAdapter> batch(VoiceProvider provider) {
        return adapter(provider) instanceof BatchTranscriptionAdapter batch ? Optional.of(batch) : Optional.empty();
    }
    Optional<SpeechSynthesisAdapter> synthesis(VoiceProvider provider) {
        return adapter(provider) instanceof SpeechSynthesisAdapter synthesis ? Optional.of(synthesis) : Optional.empty();
    }
}
