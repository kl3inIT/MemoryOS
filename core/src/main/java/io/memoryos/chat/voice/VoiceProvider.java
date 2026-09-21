package io.memoryos.chat.voice;

import java.util.List;

/**
 * Implemented speech protocols (Onyx voice_provider parity). A provider exists only together with its adapter.
 * Model and voice lists are suggestions; administrators may enter any identifier the provider accepts.
 */
public enum VoiceProvider {
    OPENAI("https://api.openai.com/v1", true, false, true,
            List.of("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe"),
            List.of("tts-1", "tts-1-hd"),
            List.of("alloy", "echo", "fable", "onyx", "nova", "shimmer")),
    /** Self-hosted or gateway servers exposing the OpenAI audio protocol, such as faster-whisper or Kokoro. */
    OPENAI_COMPATIBLE("", false, true, true, List.of(), List.of(), List.of()),
    /** ElevenLabs Scribe and streamed speech over REST; voices are voice IDs from the account's library. */
    ELEVENLABS("https://api.elevenlabs.io/v1", true, false, true,
            List.of("scribe_v2", "scribe_v1"),
            List.of("eleven_multilingual_v2", "eleven_flash_v2_5", "eleven_turbo_v2_5"),
            List.of()),
    /**
     * Azure AI Speech over REST. The endpoint is the Speech resource endpoint; recognition has no model choice and
     * speech uses neural voices, labelled {@code default} and {@code neural} as in Onyx.
     */
    AZURE("", true, true, true, List.of("default"), List.of("neural"),
            List.of("vi-VN-HoaiMyNeural", "vi-VN-NamMinhNeural", "en-US-JennyNeural", "en-US-GuyNeural")),
    /**
     * Soniox speech-to-text only: the realtime WebSocket for live audio and the async file API for recordings. The
     * endpoint is the REST base; the realtime host is derived from it ({@code api.} becomes {@code stt-rt.}).
     */
    SONIOX("https://api.soniox.com/v1", true, false, false, List.of("stt-rt-v5"), List.of(), List.of());

    private final String defaultEndpoint;
    private final boolean requiresKey;
    private final boolean requiresEndpoint;
    private final boolean speech;
    private final List<String> sttModels;
    private final List<String> ttsModels;
    private final List<String> voices;

    VoiceProvider(String defaultEndpoint, boolean requiresKey, boolean requiresEndpoint, boolean speech,
                  List<String> sttModels, List<String> ttsModels, List<String> voices) {
        this.defaultEndpoint = defaultEndpoint;
        this.requiresKey = requiresKey;
        this.requiresEndpoint = requiresEndpoint;
        this.speech = speech;
        this.sttModels = sttModels;
        this.ttsModels = ttsModels;
        this.voices = voices;
    }

    public String defaultEndpoint() { return defaultEndpoint; }
    public boolean requiresKey() { return requiresKey; }
    public boolean requiresEndpoint() { return requiresEndpoint; }
    /** Whether the provider can read text aloud; speech-to-text is common to every provider. */
    public boolean speech() { return speech; }
    public List<String> sttModels() { return sttModels; }
    public List<String> ttsModels() { return ttsModels; }
    public List<String> voices() { return voices; }

    /** The configured endpoint, or the provider's public API when none is configured, without trailing slashes. */
    public String baseUrl(String endpoint) {
        return (endpoint.isEmpty() ? defaultEndpoint : endpoint).replaceAll("/+$", "");
    }
}
