package io.memoryos.voice;

import java.io.IOException;
import java.net.http.HttpClient;
import org.jspecify.annotations.Nullable;

/**
 * One speech provider's protocol. Every provider verifies a connection and transcribes a short WAV clip; the other
 * functions are the interfaces extending this one that its class implements. {@link VoiceAdapterRegistry} holds
 * exactly one class per {@link VoiceProvider}. The classes live beside the provider clients, which are package-private
 * in this module by design.
 */
interface VoiceAdapter {
    VoiceProvider provider();
    VoiceProviderCapabilities capabilities();

    /** An authorized listing proves the endpoint and credential; it does not certify configured models or voices. */
    void verify(VoiceConnectionService.Probe probe);

    /** Transcribes one 24 kHz WAV upload. */
    String transcribe(HttpClient http, VoiceConnectionService.Connection connection, String key, @Nullable String language,
                      byte[] wav) throws IOException, InterruptedException;
}
