package io.memoryos.voice;

import io.memoryos.shared.Sha256;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI Realtime transcription with an in-memory batch fallback. Provider text and audio never leave this session
 * except through the transcript listener, and are never logged.
 */
final class OpenAiRealtimeTranscriber implements TranscriptionSession {
    static final String MODEL = "gpt-live-transcribe";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FINAL_TIMEOUT = Duration.ofSeconds(8);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DELTA = "conversation.item.input_audio_transcription.delta";
    private static final String COMPLETED = "conversation.item.input_audio_transcription.completed";

    @FunctionalInterface
    interface Connector {
        CompletableFuture<WebSocket> connect(URI uri, String authorization, String safetyIdentifier,
                                             WebSocket.Listener listener);
    }

    private final Function<byte[], String> batchTranscribe;
    private final Consumer<Transcript> listener;
    private final Runnable release;
    private final MeterRegistry meters;
    private final ByteArrayOutputStream recording = new ByteArrayOutputStream();
    private final AtomicBoolean released = new AtomicBoolean();
    private final CompletableFuture<String> finalTranscript = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();
    private final ProviderListener providerListener = new ProviderListener();
    private WebSocket socket;
    private CompletableFuture<WebSocket> sends;
    private ChunkedTranscriber fallback;
    private boolean failed;
    private boolean finishing;
    private boolean closed;

    static OpenAiRealtimeTranscriber open(String baseUrl, String key, @Nullable String language, String safetyIdentifier,
                                          Function<byte[], String> batchTranscribe, Consumer<Transcript> listener,
                                          Runnable release, MeterRegistry meters) {
        return open(baseUrl, key, language, safetyIdentifier, batchTranscribe, listener, release, meters,
                OpenAiRealtimeTranscriber::connect);
    }

    static OpenAiRealtimeTranscriber open(String baseUrl, String key, @Nullable String language, String safetyIdentifier,
                                          Function<byte[], String> batchTranscribe, Consumer<Transcript> listener,
                                          Runnable release, MeterRegistry meters, Connector connector) {
        var session = new OpenAiRealtimeTranscriber(batchTranscribe, listener, release, meters);
        session.start(baseUrl, key, language, safetyIdentifier, connector);
        return session;
    }

    private OpenAiRealtimeTranscriber(Function<byte[], String> batchTranscribe, Consumer<Transcript> listener,
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
        if (failed) {
            startFallback();
            return;
        }
        String message = JSON.writeValueAsString(Map.of(
                "type", "input_audio_buffer.append",
                "audio", Base64.getEncoder().encodeToString(pcm)));
        sends = sends.thenCompose(ignored -> socket.sendText(message, true));
        sends.whenComplete((ignored, failure) -> {
            if (failure != null) providerFailed();
        });
    }

    @Override
    public CompletableFuture<String> finish() {
        CompletableFuture<String> result;
        synchronized (this) {
            if (closed || finishing)
                return CompletableFuture.failedFuture(new IllegalStateException("Transcription already finished"));
            finishing = true;
            if (failed || fallback != null) return finishFallback();
            String commit = JSON.writeValueAsString(Map.of("type", "input_audio_buffer.commit"));
            result = sends.thenCompose(ignored -> socket.sendText(commit, true))
                    .thenCompose(ignored -> finalTranscript)
                    .orTimeout(FINAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        return result.handle((text, failure) -> failure == null
                        ? CompletableFuture.completedFuture(text)
                        : finishFallback())
                .thenCompose(Function.identity());
    }

    @Override
    public void close() {
        WebSocket live;
        ChunkedTranscriber batch;
        synchronized (this) {
            if (closed) return;
            closed = true;
            live = socket;
            batch = fallback;
            recording.reset();
        }
        if (live != null && !live.isOutputClosed()) live.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        if (batch != null) batch.close();
        if (released.compareAndSet(false, true)) release.run();
    }

    private void start(String baseUrl, String key, @Nullable String language, String safetyIdentifier, Connector connector) {
        try {
            socket = connector.connect(realtimeUri(baseUrl), "Bearer " + key, hash(safetyIdentifier), providerListener)
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            sends = CompletableFuture.completedFuture(socket);
            var transcription = new LinkedHashMap<String, Object>();
            transcription.put("model", MODEL);
            transcription.put("delay", "low");
            if (language != null) transcription.put("languages", List.of(language));
            var input = new LinkedHashMap<String, Object>();
            input.put("format", Map.of("type", "audio/pcm", "rate", 24_000));
            input.put("transcription", transcription);
            input.put("turn_detection", null);
            var session = Map.of("type", "transcription", "audio", Map.of("input", input));
            socket.sendText(JSON.writeValueAsString(Map.of("type", "session.update", "session", session)), true)
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            closed = true;
            if (socket != null && !socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "unavailable");
            throw new IllegalStateException("Realtime transcription unavailable", failed);
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
        meters.counter("memoryos.chat.voice.realtime.fallback", "provider", VoiceProvider.OPENAI.name()).increment();
        if (socket != null && !socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE, "fallback");
    }

    private void providerFailed() {
        synchronized (this) {
            if (closed || failed) return;
            failed = true;
            finalTranscript.completeExceptionally(new IllegalStateException("Realtime transcription unavailable"));
            if (!finishing) startFallback();
        }
    }

    private void providerMessage(String message) {
        String update = null;
        try {
            var event = JSON.readTree(message);
            String type = event.path("type").asString("");
            if (DELTA.equals(type)) {
                String delta = event.path("delta").asString("");
                if (!delta.isEmpty()) {
                    synchronized (this) {
                        if (failed || closed) return;
                        partial.append(delta);
                        update = partial.toString();
                    }
                }
            } else if (COMPLETED.equals(type)) {
                String transcript = event.path("transcript").asString("").strip();
                synchronized (this) {
                    if (!transcript.isEmpty()) {
                        partial.setLength(0);
                        partial.append(transcript);
                    }
                    finalTranscript.complete(partial.toString());
                }
            } else if ("error".equals(type)) {
                providerFailed();
            }
        } catch (RuntimeException malformed) {
            providerFailed();
        }
        if (update != null) listener.accept(new Transcript(update, false, false));
    }

    private static CompletableFuture<WebSocket> connect(URI uri, String authorization, String safetyIdentifier,
                                                         WebSocket.Listener listener) {
        return HTTP.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT)
                .header("Authorization", authorization)
                .header("OpenAI-Safety-Identifier", safetyIdentifier)
                .buildAsync(uri, listener);
    }

    private static URI realtimeUri(String baseUrl) {
        String value = baseUrl.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://")
                .replaceAll("/+$", "");
        return URI.create(value + "/realtime?model=" + MODEL);
    }

    private static String hash(String value) {
        return Sha256.hex(value);
    }

    private final class ProviderListener implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (message) {
                message.append(data);
                if (last) {
                    String complete = message.toString();
                    message.setLength(0);
                    providerMessage(complete);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            providerFailed();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (OpenAiRealtimeTranscriber.this) {
                if (!closed && !finalTranscript.isDone()) providerFailed();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            providerFailed();
        }
    }
}
