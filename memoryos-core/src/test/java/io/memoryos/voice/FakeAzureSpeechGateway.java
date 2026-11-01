package io.memoryos.voice;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Records what the Speech SDK would receive and lets a test play its events; no native library loads. */
final class FakeAzureSpeechGateway implements AzureSpeechGateway {
    final List<AzureSpeechTarget> targets = new ArrayList<>();
    final List<List<String>> locales = new ArrayList<>();
    final ByteArrayOutputStream written = new ByteArrayOutputStream();
    final List<String> ssml = new ArrayList<>();
    final Deque<List<byte[]>> speeches = new ArrayDeque<>();
    @Nullable RecognitionListener listener;
    boolean unavailable;
    boolean stopped;
    int recognitionsClosed;
    int speechesClosed;
    @Nullable Runnable onStop;

    @Override
    public Recognition recognize(AzureSpeechTarget target, String key, List<String> locales, RecognitionListener listener) {
        if (unavailable) throw new IllegalStateException("Azure speech recognition unavailable");
        targets.add(target);
        this.locales.add(locales);
        this.listener = listener;
        return new Recognition() {
            @Override public void write(byte[] pcm16k) { written.writeBytes(pcm16k); }
            @Override public void stop() {
                if (onStop != null) onStop.run();
                stopped = true;
            }
            @Override public void close() { recognitionsClosed++; }
        };
    }

    @Override
    public Speech speak(AzureSpeechTarget target, String key, String document) {
        if (unavailable) throw new IllegalStateException("Azure speech synthesis unavailable");
        targets.add(target);
        ssml.add(document);
        var chunks = new ArrayDeque<>(speeches.isEmpty() ? List.<byte[]>of() : speeches.poll());
        return new Speech() {
            @Override public byte @Nullable [] read() { return chunks.poll(); }
            @Override public void close() { speechesClosed++; }
        };
    }
}
