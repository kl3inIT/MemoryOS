package io.memoryos.voice;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Azure AI Speech over REST (MEM-91 decision Q1; the Speech SDK is MEM-137). The endpoint is the Speech resource
 * endpoint; recognition has no model choice and speech uses neural voices, labelled {@code default} and {@code neural}
 * as in Onyx. An uploaded recording is not transcribed.
 */
@Component
final class AzureVoiceAdapter implements SpeechSynthesisAdapter {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final VoiceProviderCapabilities CAPABILITIES = new VoiceProviderCapabilities("", true, true,
            List.of("default"), List.of("neural"),
            List.of("vi-VN-HoaiMyNeural", "vi-VN-NamMinhNeural", "en-US-JennyNeural", "en-US-GuyNeural"));

    @Override public VoiceProvider provider() { return VoiceProvider.AZURE; }
    @Override public VoiceProviderCapabilities capabilities() { return CAPABILITIES; }

    @Override public void verify(VoiceConnectionService.Probe probe) {
        VoiceChecks.arrayListing(probe.baseUrl() + AzureSpeech.VOICES_PATH, "Ocp-Apim-Subscription-Key", probe.key());
    }

    @Override public String transcribe(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                       @Nullable String language, byte[] wav) throws IOException, InterruptedException {
        return AzureSpeech.transcribe(http, CAPABILITIES.baseUrl(connection.endpoint()), key, language, wav,
                Pcm16.WAV_HEADER_BYTES, wav.length - Pcm16.WAV_HEADER_BYTES, REQUEST_TIMEOUT);
    }

    @Override public ProviderSpeech speech(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                           double speed, Duration timeout) {
        String base = CAPABILITIES.baseUrl(connection.endpoint());
        return ProviderSpeech.http(http, text -> AzureSpeech.speech(base, key, connection.ttsVoice(), speed, text, timeout));
    }
}
