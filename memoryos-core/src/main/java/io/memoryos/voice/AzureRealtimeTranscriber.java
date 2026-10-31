package io.memoryos.voice;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Azure continuous recognition through the Speech SDK, with the same in-memory batch fallback as the other realtime
 * transcribers. Audio arrives as 24 kHz PCM16 and is resampled to the 16 kHz the SDK's push stream takes. Each
 * utterance the provider ends by silence is committed text and an utterance boundary; the utterance being spoken is
 * only a preview. Provider text and audio are never logged.
 */
final class AzureRealtimeTranscriber implements TranscriptionSession, AzureSpeechGateway.RecognitionListener {
    /** Three 24 kHz samples resample to two 16 kHz samples; a remainder waits for the next frame. */
    private static final int RESAMPLE_BLOCK_BYTES = 6;
    private static final Executor FINISHER = runnable -> Thread.ofVirtual().name("voice-azure-finish").start(runnable);

    private final Function<byte[], String> batchTranscribe;
    private final Consumer<Transcript> listener;
    private final Runnable release;
    private final MeterRegistry meters;
    private final ByteArrayOutputStream recording = new ByteArrayOutputStream();
    private final AtomicBoolean released = new AtomicBoolean();
    private final StringBuilder committed = new StringBuilder();
    private AzureSpeechGateway.@Nullable Recognition recognition;
    private @Nullable ChunkedTranscriber fallback;
    private byte[] carry = new byte[0];
    private boolean failed;
    private boolean finishing;
    private boolean closed;

    static AzureRealtimeTranscriber open(AzureSpeechGateway gateway, AzureSpeechTarget target, String key,
                                         List<String> locales, Function<byte[], String> batchTranscribe,
                                         Consumer<Transcript> listener, Runnable release, MeterRegistry meters) {
        var session = new AzureRealtimeTranscriber(batchTranscribe, listener, release, meters);
        var started = gateway.recognize(target, key, locales, session);
        synchronized (session) {
            session.recognition = started;
        }
        return session;
    }

    private AzureRealtimeTranscriber(Function<byte[], String> batchTranscribe, Consumer<Transcript> listener,
                                     Runnable release, MeterRegistry meters) {
        this.batchTranscribe = batchTranscribe;
        this.listener = listener;
        this.release = release;
        this.meters = meters;
    }

    @Override
    public synchronized void append(byte[] pcm) {
        if (closed || finishing) throw new IllegalStateException("Transcription no longer accepts audio");
        if (pcm.length % 2 != 0) throw new IllegalArgumentException("PCM16 audio must contain whole samples");
        if (fallback != null) {
            fallback.append(pcm);
            return;
        }
        recording.writeBytes(pcm);
        if (failed || recognition == null) {
            startFallback();
            return;
        }
        byte[] audio = new byte[carry.length + pcm.length];
        System.arraycopy(carry, 0, audio, 0, carry.length);
        System.arraycopy(pcm, 0, audio, carry.length, pcm.length);
        int whole = audio.length - audio.length % RESAMPLE_BLOCK_BYTES;
        carry = Arrays.copyOfRange(audio, whole, audio.length);
        if (whole == 0) return;
        try {
            recognition.write(Pcm16.resampleTo16k(audio, 0, whole));
        } catch (RuntimeException unavailable) {
            startFallback();
        }
    }

    @Override
    public CompletableFuture<String> finish() {
        AzureSpeechGateway.Recognition live;
        synchronized (this) {
            if (closed || finishing)
                return CompletableFuture.failedFuture(new IllegalStateException("Transcription already finished"));
            finishing = true;
            if (failed || fallback != null || recognition == null) return finishFallback();
            live = recognition;
        }
        return CompletableFuture.supplyAsync(() -> {
            live.stop();
            synchronized (this) {
                if (failed) throw new IllegalStateException("Azure speech recognition failed");
                return committed.toString().strip();
            }
        }, FINISHER).handle((text, failure) -> failure == null
                ? CompletableFuture.completedFuture(text)
                : finishFallback()).thenCompose(Function.identity());
    }

    @Override
    public void close() {
        AzureSpeechGateway.Recognition live;
        ChunkedTranscriber batch;
        synchronized (this) {
            if (closed) return;
            closed = true;
            live = recognition;
            batch = fallback;
            recording.reset();
        }
        if (live != null) live.close();
        if (batch != null) batch.close();
        if (released.compareAndSet(false, true)) release.run();
    }

    @Override
    public void recognizing(String text) {
        String update;
        synchronized (this) {
            if (failed || closed) return;
            update = join(committed.toString(), text);
        }
        if (!update.isEmpty()) listener.accept(new Transcript(update, false, false));
    }

    @Override
    public void recognized(String text) {
        String update;
        synchronized (this) {
            if (failed || closed) return;
            if (!committed.isEmpty()) committed.append(' ');
            committed.append(text.strip());
            update = committed.toString();
        }
        // Azure ended the utterance on silence: a genuine boundary for Auto-Send, not a client pause.
        listener.accept(new Transcript(update, false, true));
    }

    @Override
    public void failed() {
        synchronized (this) {
            if (closed || failed) return;
            failed = true;
            if (!finishing) startFallback();
        }
    }

    private synchronized CompletableFuture<String> finishFallback() {
        startFallback();
        return fallback.finish();
    }

    /** Caller holds this instance's monitor. */
    private void startFallback() {
        if (fallback != null) return;
        failed = true;
        fallback = new ChunkedTranscriber(batchTranscribe, listener, () -> {});
        byte[] audio = recording.toByteArray();
        recording.reset();
        if (audio.length > 0) fallback.append(audio);
        meters.counter("memoryos.chat.voice.realtime.fallback", "provider", VoiceProvider.AZURE.name()).increment();
        if (recognition != null) recognition.close();
    }

    private static String join(String committed, String preview) {
        String next = preview.strip();
        return committed.isEmpty() ? next : next.isEmpty() ? committed : committed + " " + next;
    }
}
