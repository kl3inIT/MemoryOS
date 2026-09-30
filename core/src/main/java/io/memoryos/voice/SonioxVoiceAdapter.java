package io.memoryos.voice;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Soniox speech-to-text only: the realtime WebSocket for dictation and meetings, and the async file API for clips and
 * recordings. The endpoint is the REST base; the realtime host is derived from it ({@code api.} becomes
 * {@code stt-rt.}). Only Soniox separates speakers.
 */
@Component
final class SonioxVoiceAdapter implements RealtimeTranscriptionAdapter, LiveTranscriptionAdapter, BatchTranscriptionAdapter {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final VoiceProviderCapabilities CAPABILITIES = new VoiceProviderCapabilities("https://api.soniox.com/v1",
            true, false, List.of("stt-rt-v5"), List.of(), List.of());

    @Override public VoiceProvider provider() { return VoiceProvider.SONIOX; }
    @Override public VoiceProviderCapabilities capabilities() { return CAPABILITIES; }

    /** Soniox has no model listing; an authorized one-item transcription listing proves the key (Anarlog provider validation). */
    @Override public void verify(VoiceConnectionService.Probe probe) {
        VoiceChecks.jsonListing(probe.baseUrl() + "/transcriptions?limit=1", Map.of("Authorization", "Bearer " + probe.key()),
                "transcriptions");
    }

    @Override public String transcribe(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                       @Nullable String language, byte[] wav) throws IOException, InterruptedException {
        return SonioxAsync.transcribe(http, CAPABILITIES.baseUrl(connection.endpoint()), key, connection.sttModel(), language,
                wav, REQUEST_TIMEOUT);
    }

    @Override public Optional<TranscriptionSession> openRealtime(VoiceConnectionService.Connection connection, String key,
            @Nullable String language, String user, Function<byte[], String> batch, Consumer<Transcript> listener,
            Runnable release, MeterRegistry meters) {
        return Optional.of(SonioxRealtimeTranscriber.open(CAPABILITIES.baseUrl(connection.endpoint()), key,
                connection.sttModel(), language, batch, listener, release, meters));
    }

    @Override public LiveTranscription openLive(VoiceConnectionService.Connection connection, String key,
            LiveTranscription.Options options, long offsetMs, LiveTranscription.Listener listener) {
        return SonioxLiveTranscription.open(CAPABILITIES.baseUrl(connection.endpoint()), key, connection.sttModel(), options,
                offsetMs, listener);
    }

    @Override public boolean diarizes() { return true; }
    @Override public long maxBytes() { return 500L * 1024 * 1024; }

    @Override public List<LiveTranscription.Segment> segments(HttpClient http, VoiceConnectionService.Connection connection,
            String key, LiveTranscription.Options options, boolean diarize, BatchTranscriptionService.Recording recording,
            Duration timeout) throws IOException, InterruptedException {
        return SonioxAsync.segments(http, CAPABILITIES.baseUrl(connection.endpoint()), key, connection.sttModel(),
                options.language(), options.terms(), diarize, recording, timeout);
    }
}
