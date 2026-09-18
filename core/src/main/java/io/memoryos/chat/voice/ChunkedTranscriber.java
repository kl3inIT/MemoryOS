package io.memoryos.chat.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Onyx ChunkedTranscriber parity for providers without live transcription. Audio is transcribed in three-second
 * windows for interim text and silent windows never reach the provider. Finishing transcribes the silence-trimmed
 * recording once for the final text. Audio lives only in this object and is released when it closes.
 */
public final class ChunkedTranscriber implements TranscriptionSession {
    static final int WINDOW_BYTES = Pcm16.BYTES_PER_SECOND * 3;
    /** A slow provider skips interim windows instead of queueing audio; the final pass still covers everything. */
    private static final int MAX_PENDING_WINDOWS = 4;
    private final Function<byte[], String> transcribe;
    private final Consumer<Transcript> listener;
    private final Runnable release;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("voice-transcription").factory());
    private final List<String> windows = new ArrayList<>();
    private final List<Future<?>> pending = new ArrayList<>();
    private byte[] recording = new byte[WINDOW_BYTES];
    private int length;
    private int windowStart;
    private boolean finishing;
    private boolean closed;

    /**
     * @param transcribe blocking provider call from a WAV upload to text; it runs on this session's worker only
     * @param listener receives interim transcripts in order
     * @param release returns the session's concurrency slot when the transcriber closes
     */
    ChunkedTranscriber(Function<byte[], String> transcribe, Consumer<Transcript> listener, Runnable release) {
        this.transcribe = transcribe;
        this.listener = listener;
        this.release = release;
    }

    /** Appends whole PCM16 samples. */
    public synchronized void append(byte[] pcm) {
        if (closed || finishing) throw new IllegalStateException("Transcription no longer accepts audio");
        if (pcm.length % 2 != 0) throw new IllegalArgumentException("PCM16 audio must contain whole samples");
        if (length + pcm.length > recording.length) {
            recording = java.util.Arrays.copyOf(recording, Math.max(recording.length * 2, length + pcm.length));
        }
        System.arraycopy(pcm, 0, recording, length, pcm.length);
        length += pcm.length;
        while (length - windowStart >= WINDOW_BYTES) {
            int start = windowStart;
            windowStart += WINDOW_BYTES;
            if (!Pcm16.hasSpeech(recording, start, WINDOW_BYTES)) continue;
            pending.removeIf(Future::isDone);
            if (pending.size() >= MAX_PENDING_WINDOWS) continue;
            byte[] wav = Pcm16.wav(recording, start, WINDOW_BYTES);
            pending.add(worker.submit(() -> interim(wav)));
        }
    }

    /** Stops accepting audio and completes with the final text of the whole recording. */
    public CompletableFuture<String> finish() {
        byte[] audio;
        int size;
        List<String> interim;
        synchronized (this) {
            if (closed || finishing) return CompletableFuture.failedFuture(new IllegalStateException("Transcription already finished"));
            finishing = true;
            pending.forEach(window -> window.cancel(false));
            pending.clear();
            audio = recording;
            size = length;
            interim = List.copyOf(windows);
        }
        var result = new CompletableFuture<String>();
        worker.execute(() -> {
            try {
                byte[] voiced = Pcm16.trimSilence(audio, size);
                result.complete(voiced.length == 0 ? String.join(" ", interim)
                        : transcribe.apply(Pcm16.wav(voiced, 0, voiced.length)).strip());
            } catch (RuntimeException failed) {
                // Onyx fallback: interim window text remains usable when the final pass fails.
                if (interim.isEmpty()) result.completeExceptionally(failed);
                else result.complete(String.join(" ", interim));
            }
        });
        return result;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            recording = new byte[0];
            windows.clear();
            pending.clear();
        }
        worker.shutdownNow();
        release.run();
    }

    private void interim(byte[] wav) {
        String text;
        try {
            text = transcribe.apply(wav).strip();
        } catch (RuntimeException failed) {
            return; // Interim text is best effort; the final pass retries all audio.
        }
        if (text.isEmpty()) return;
        String joined;
        synchronized (this) {
            if (finishing || closed) return;
            windows.add(text);
            joined = String.join(" ", windows);
        }
        listener.accept(new Transcript(joined, false));
    }
}
