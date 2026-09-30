package io.memoryos.voice;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * ElevenLabs Scribe and streamed speech over REST; voices are voice IDs from the account's library. An uploaded
 * recording is not transcribed: the current call posts WAV under a fixed name, and another container needs its own
 * work.
 */
@Component
final class ElevenLabsVoiceAdapter implements SpeechSynthesisAdapter {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final VoiceProviderCapabilities CAPABILITIES = new VoiceProviderCapabilities("https://api.elevenlabs.io/v1",
            true, false, List.of("scribe_v2", "scribe_v1"),
            List.of("eleven_multilingual_v2", "eleven_flash_v2_5", "eleven_turbo_v2_5"), List.of());

    @Override public VoiceProvider provider() { return VoiceProvider.ELEVENLABS; }
    @Override public VoiceProviderCapabilities capabilities() { return CAPABILITIES; }

    @Override public void verify(VoiceConnectionService.Probe probe) {
        VoiceChecks.arrayListing(probe.baseUrl() + "/models", "xi-api-key", probe.key());
    }

    @Override public String transcribe(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                       @Nullable String language, byte[] wav) throws IOException, InterruptedException {
        return ElevenLabsVoice.transcribe(http, CAPABILITIES.baseUrl(connection.endpoint()), key, connection.sttModel(),
                language, wav, REQUEST_TIMEOUT);
    }

    @Override public ProviderSpeech speech(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                           double speed, Duration timeout) {
        String base = CAPABILITIES.baseUrl(connection.endpoint());
        return ProviderSpeech.http(http, text -> ElevenLabsVoice.speech(base, key, connection.ttsModel(), connection.ttsVoice(),
                speed, text, timeout));
    }
}
