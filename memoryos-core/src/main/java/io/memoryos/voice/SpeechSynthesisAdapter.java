package io.memoryos.voice;

import java.net.http.HttpClient;
import java.time.Duration;

/** Reading text aloud as MP3 at the member's speed. */
interface SpeechSynthesisAdapter extends VoiceAdapter {
    ProviderSpeech speech(HttpClient http, VoiceConnectionService.Connection connection, String key, double speed,
                          Duration timeout);
}
