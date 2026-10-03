package io.memoryos.voice;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** OpenAI's public audio API: realtime dictation on its own endpoint, uploads up to 25 MB. */
@Component
final class OpenAiVoiceAdapter implements RealtimeTranscriptionAdapter, BatchTranscriptionAdapter, SpeechSynthesisAdapter {
    private static final VoiceProviderCapabilities CAPABILITIES = new VoiceProviderCapabilities("https://api.openai.com/v1",
            true, false, List.of("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe"), List.of("tts-1", "tts-1-hd"),
            List.of("alloy", "echo", "fable", "onyx", "nova", "shimmer"));

    @Override public VoiceProvider provider() { return VoiceProvider.OPENAI; }
    @Override public VoiceProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public void verify(VoiceConnectionService.Probe probe) { OpenAiAudio.verify(probe); }

    @Override public String transcribe(VoiceConnectionService.Connection connection, String key,
                                       @Nullable String language, byte[] wav) {
        return OpenAiAudio.transcribe(CAPABILITIES.baseUrl(connection.endpoint()), connection.sttModel(), key, language, wav);
    }

    /** The realtime socket exists only on OpenAI's own endpoint; a configured endpoint is a proxy that may not relay it. */
    @Override public Optional<TranscriptionSession> openRealtime(VoiceConnectionService.Connection connection, String key,
            @Nullable String language, String user, Function<byte[], String> batch, Consumer<Transcript> listener,
            Runnable release, MeterRegistry meters) {
        if (!connection.endpoint().isEmpty()) return Optional.empty();
        return Optional.of(OpenAiRealtimeTranscriber.open(CAPABILITIES.baseUrl(connection.endpoint()), key, language, user,
                batch, listener, release, meters));
    }

    @Override public boolean diarizes() { return false; }
    /** OpenAI refuses an audio upload above 25 MB. */
    @Override public long maxBytes() { return 25L * 1024 * 1024; }

    @Override public List<LiveTranscription.Segment> segments(VoiceConnectionService.Connection connection, String key,
            LiveTranscription.Options options, boolean diarize, BatchTranscriptionService.Recording recording,
            Duration timeout) {
        return OpenAiAudio.segments(CAPABILITIES.baseUrl(connection.endpoint()), connection.sttModel(), key, options,
                recording, timeout);
    }

    @Override public ProviderSpeech speech(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                           double speed, Duration timeout) {
        return OpenAiAudio.speech(CAPABILITIES.baseUrl(connection.endpoint()), connection.ttsModel(), connection.ttsVoice(),
                key, speed, timeout);
    }
}
