package io.memoryos.chat.voice;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * A long-running speech-to-text stream that yields committed segments with speaker labels and times, for recordings
 * such as meetings. Audio is PCM16 24 kHz mono and is never stored; times are milliseconds from the start of the
 * recording, including the offset the stream was opened with.
 */
public interface LiveTranscription extends AutoCloseable {
    /** Appends whole PCM16 samples. */
    void append(byte[] pcm);

    /** Stops accepting audio; completes once every committed segment has been delivered. */
    CompletableFuture<Void> finish();

    @Override
    void close();

    /** Options for one stream. Terms are domain words the provider should prefer, such as names and acronyms. */
    record Options(@Nullable String language, List<String> terms, boolean diarize) {
        public Options {
            terms = List.copyOf(terms);
        }
    }

    /** One committed piece of speech. The speaker is the provider's label within this stream, such as {@code 1}. */
    record Segment(String speaker, long startMs, long endMs, String text, double confidence) {}

    /** Receives stream events on provider threads; implementations must be quick and thread-safe. */
    interface Listener {
        /** The uncommitted text currently being spoken, replacing the previous preview; empty clears it. */
        void preview(String speaker, String text);

        void segment(Segment segment);

        /** The provider could not continue after retries; segments already delivered remain valid. */
        void failed();
    }
}
