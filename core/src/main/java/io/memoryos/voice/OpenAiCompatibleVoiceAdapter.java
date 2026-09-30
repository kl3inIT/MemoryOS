package io.memoryos.voice;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Self-hosted or gateway servers exposing the OpenAI audio protocol, such as faster-whisper or Kokoro. The endpoint is
 * required and a key is optional; recordings are bounded by what MemoryOS stores.
 */
@Component
final class OpenAiCompatibleVoiceAdapter implements BatchTranscriptionAdapter, SpeechSynthesisAdapter {
    private static final VoiceProviderCapabilities CAPABILITIES = new VoiceProviderCapabilities("", false, true,
            List.of(), List.of(), List.of());

    @Override public VoiceProvider provider() { return VoiceProvider.OPENAI_COMPATIBLE; }
    @Override public VoiceProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public void verify(VoiceConnectionService.Probe probe) { OpenAiAudio.verify(probe); }

    @Override public String transcribe(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                       @Nullable String language, byte[] wav) {
        return OpenAiAudio.transcribe(CAPABILITIES.baseUrl(connection.endpoint()), connection.sttModel(), key, language, wav);
    }

    @Override public boolean diarizes() { return false; }
    @Override public long maxBytes() { return 500L * 1024 * 1024; }

    @Override public List<LiveTranscription.Segment> segments(HttpClient http, VoiceConnectionService.Connection connection,
            String key, LiveTranscription.Options options, boolean diarize, BatchTranscriptionService.Recording recording,
            Duration timeout) throws IOException, InterruptedException {
        return OpenAiAudio.segments(http, CAPABILITIES.baseUrl(connection.endpoint()), connection.sttModel(), key, options,
                recording, timeout);
    }

    @Override public ProviderSpeech speech(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                           double speed, Duration timeout) {
        return OpenAiAudio.speech(CAPABILITIES.baseUrl(connection.endpoint()), connection.ttsModel(), connection.ttsVoice(),
                key, speed, timeout);
    }
}
