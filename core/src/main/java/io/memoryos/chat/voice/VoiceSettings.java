package io.memoryos.chat.voice;

/** One member's voice preferences (Onyx voice_auto_send, voice_auto_playback and voice_playback_speed). */
public record VoiceSettings(boolean autoSend, boolean autoPlayback, double playbackSpeed) {
    public static final VoiceSettings DEFAULT = new VoiceSettings(false, false, 1.0);
}
