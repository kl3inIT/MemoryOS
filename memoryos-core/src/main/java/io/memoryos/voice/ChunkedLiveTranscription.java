package io.memoryos.voice;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Live segments for providers without a realtime protocol: audio is cut into utterances at pauses and each one is
 * transcribed through the provider's REST adapter, like the chunked dictation path. An utterance ends after 800 ms
 * of silence following speech, or at 30 seconds; silent audio never reaches the provider.
 * These providers do not diarize, so every segment has speaker {@code 1}.
 */
final class ChunkedLiveTranscription implements LiveTranscription {
    static final int FRAME_BYTES = Pcm16.BYTES_PER_SECOND / 10;
    static final int PAUSE_FRAMES = 8;
    static final int MAX_SEGMENT_BYTES = Pcm16.BYTES_PER_SECOND * 30;
    private final Function<byte[], String> transcribe;
    private final Listener listener;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("voice-live-chunk").factory());
    private final long offsetMs;
    private byte[] pending = new byte[0];
    private byte[] segment = new byte[MAX_SEGMENT_BYTES];
    private int segmentLength;
    private long segmentStartBytes;
    private long totalBytes;
    private int silentFrames;
    private boolean speech;
    private CompletableFuture<Void> work = CompletableFuture.completedFuture(null);
    private boolean finishing;
    private boolean closed;

    ChunkedLiveTranscription(Function<byte[], String> transcribe, long offsetMs, Listener listener) {
        this.transcribe = transcribe;
        this.offsetMs = offsetMs;
        this.listener = listener;
    }

    @Override
    public synchronized void append(byte[] pcm) {
        if (closed || finishing) throw new IllegalStateException("Transcription no longer accepts audio");
        if (pcm.length % 2 != 0) throw new IllegalArgumentException("PCM16 audio must contain whole samples");
        byte[] bytes = pending.length == 0 ? pcm : concat(pending, pcm);
        int offset = 0;
        while (bytes.length - offset >= FRAME_BYTES) {
            frame(bytes, offset);
            offset += FRAME_BYTES;
        }
        pending = Arrays.copyOfRange(bytes, offset, bytes.length);
    }

    @Override
    public CompletableFuture<Void> finish() {
        synchronized (this) {
            if (closed) return CompletableFuture.completedFuture(null);
            if (!finishing) {
                // A trailing partial frame is under 100 ms and carries no words.
                finishing = true;
                cut();
            }
            return work;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            segment = new byte[0];
            pending = new byte[0];
        }
        worker.shutdownNow();
    }

    /** Caller holds the monitor. */
    private void frame(byte[] bytes, int offset) {
        boolean voiced = Pcm16.hasSpeech(bytes, offset, FRAME_BYTES);
        if (!speech && !voiced) {
            totalBytes += FRAME_BYTES;
            return;
        }
        if (!speech) {
            speech = true;
            segmentStartBytes = totalBytes;
            segmentLength = 0;
        }
        System.arraycopy(bytes, offset, segment, segmentLength, FRAME_BYTES);
        segmentLength += FRAME_BYTES;
        totalBytes += FRAME_BYTES;
        silentFrames = voiced ? 0 : silentFrames + 1;
        if (silentFrames >= PAUSE_FRAMES || segmentLength + FRAME_BYTES > MAX_SEGMENT_BYTES) cut();
    }

    /** Caller holds the monitor. Sends the current utterance to the provider in order. */
    private void cut() {
        if (!speech || segmentLength == 0) return;
        byte[] wav = Pcm16.wav(segment, 0, segmentLength);
        long start = offsetMs + segmentStartBytes * 1000 / Pcm16.BYTES_PER_SECOND;
        long end = offsetMs + (segmentStartBytes + segmentLength) * 1000 / Pcm16.BYTES_PER_SECOND;
        speech = false;
        silentFrames = 0;
        segmentLength = 0;
        work = work.thenRunAsync(() -> {
            String text;
            try {
                text = transcribe.apply(wav).strip();
            } catch (RuntimeException failed) {
                listener.failed();
                return;
            }
            if (!text.isEmpty()) listener.segment(new Segment("1", start, end, text, 1.0));
        }, worker);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
