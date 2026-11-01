package io.memoryos.voice;

/** A meeting's continuous stream on the provider's own live protocol, which can separate speakers. */
interface LiveTranscriptionAdapter extends VoiceAdapter {
    LiveTranscription openLive(VoiceConnectionService.Connection connection, String key, LiveTranscription.Options options,
                               long offsetMs, LiveTranscription.Listener listener);
}
