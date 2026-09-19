package io.memoryos.chat.voice;

import java.util.concurrent.CompletableFuture;

/** One authorized speech-to-text session, independent of whether its provider streams or accepts batch audio. */
public interface TranscriptionSession extends AutoCloseable {
    /** Appends whole PCM16 little-endian samples at 24 kHz mono. */
    void append(byte[] pcm);

    /** Stops accepting audio and completes with the authoritative transcript for the whole recording. */
    CompletableFuture<String> finish();

    @Override
    void close();
}
