package io.memoryos.chat.voice;

import io.memoryos.chat.ChatException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Streaming read-aloud for text that arrives in parts, such as an answer being generated (Onyx streaming TTS parity).
 * Parts are synthesized in order on one worker and their audio handed to the listener as it arrives; nothing is kept.
 */
public final class StreamingSynthesizer implements AutoCloseable {
    private final Function<String, Stream<byte[]>> synthesize;
    private final Consumer<byte[]> audio;
    private final Consumer<String> release;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("voice-synthesis").factory());
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private @Nullable Stream<byte[]> current;
    private int length;
    private boolean finishing;
    private boolean closed;

    /**
     * @param synthesize lazy provider audio for one part; it is consumed on this speech's worker only
     * @param audio receives audio chunks in text order
     * @param release receives the outcome ({@code succeeded}, {@code failed} or {@code cancelled}) when the speech closes
     */
    StreamingSynthesizer(Function<String, Stream<byte[]>> synthesize, Consumer<byte[]> audio, Consumer<String> release) {
        this.synthesize = synthesize;
        this.audio = audio;
        this.release = release;
    }

    /** Queues one part; parts are read in the order they are appended. Blank parts are ignored. */
    public synchronized void append(String text) {
        if (closed || finishing) throw new IllegalStateException("Speech no longer accepts text");
        if (text.length() > VoiceSynthesisService.MAX_SEGMENT_LENGTH)
            throw ChatException.invalid("A part to read aloud must contain at most 4096 characters.");
        if (length + text.length() > VoiceSynthesisService.MAX_TEXT_LENGTH)
            throw ChatException.invalid("Text to read aloud must contain at most 32000 characters.");
        String part = text.strip();
        if (part.isEmpty()) return;
        length += text.length();
        worker.execute(() -> speak(part));
    }

    /** Completes after every queued part has been read, or exceptionally when the provider fails. */
    public synchronized CompletableFuture<Void> finish() {
        if (closed || finishing) return CompletableFuture.failedFuture(new IllegalStateException("Speech already finished"));
        finishing = true;
        worker.execute(() -> finished.complete(null));
        return finished;
    }

    private void speak(String part) {
        Stream<byte[]> chunks;
        synchronized (this) {
            if (closed || finished.isDone()) return;
            chunks = synthesize.apply(part);
            current = chunks;
        }
        try {
            chunks.forEach(audio);
        } catch (RuntimeException failed) {
            // Provider payloads may carry account detail; report unavailability instead.
            if (!isClosed()) finished.completeExceptionally(ChatException.providerUnavailable());
        } finally {
            synchronized (this) {
                current = null;
            }
            chunks.close();
        }
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        Stream<byte[]> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = current;
        }
        if (active != null) active.close();
        worker.shutdownNow();
        String outcome = !finished.isDone() ? "cancelled" : finished.isCompletedExceptionally() ? "failed" : "succeeded";
        finished.cancel(false);
        release.accept(outcome);
    }
}
