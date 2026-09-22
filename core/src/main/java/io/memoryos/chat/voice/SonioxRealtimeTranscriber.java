package io.memoryos.chat.voice;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Soniox realtime transcription with an in-memory batch fallback, following the Soniox WebSocket API as Anarlog uses
 * it: the key and configuration travel in the first text message, audio as raw PCM16 binary frames, and the stream
 * ends with a finalize message and an empty frame, answered by {@code finished}. Final tokens are committed text;
 * non-final tokens only extend the current preview. Provider text and audio are never logged.
 */
final class SonioxRealtimeTranscriber implements TranscriptionSession {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FINAL_TIMEOUT = Duration.ofSeconds(8);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    interface Connector {
        CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener);
    }

    private final Function<byte[], String> batchTranscribe;
    private final Consumer<Transcript> listener;
    private final Runnable release;
    private final MeterRegistry meters;
    private final ByteArrayOutputStream recording = new ByteArrayOutputStream();
    private final AtomicBoolean released = new AtomicBoolean();
    private final CompletableFuture<String> finalTranscript = new CompletableFuture<>();
    private final StringBuilder committed = new StringBuilder();
    private final ProviderListener providerListener = new ProviderListener();
    private WebSocket socket;
    private CompletableFuture<WebSocket> sends;
    private ChunkedTranscriber fallback;
    private String preview = "";
    private boolean failed;
    private boolean finishing;
    private boolean closed;

    static SonioxRealtimeTranscriber open(String baseUrl, String key, String model, @Nullable String language,
                                          Function<byte[], String> batchTranscribe, Consumer<Transcript> listener,
                                          Runnable release, MeterRegistry meters) {
        return open(baseUrl, key, model, language, batchTranscribe, listener, release, meters,
                SonioxRealtimeTranscriber::connect);
    }

    static SonioxRealtimeTranscriber open(String baseUrl, String key, String model, @Nullable String language,
                                          Function<byte[], String> batchTranscribe, Consumer<Transcript> listener,
                                          Runnable release, MeterRegistry meters, Connector connector) {
        var session = new SonioxRealtimeTranscriber(batchTranscribe, listener, release, meters);
        session.start(realtimeUri(baseUrl), config(key, model, language), connector);
        return session;
    }

    private SonioxRealtimeTranscriber(Function<byte[], String> batchTranscribe, Consumer<Transcript> listener,
                                      Runnable release, MeterRegistry meters) {
        this.batchTranscribe = batchTranscribe;
        this.listener = listener;
        this.release = release;
        this.meters = meters;
    }

    /** The first message: the key and a PCM16 mono 24 kHz stream, with the language as a strict hint when known. */
    static String config(String key, String model, @Nullable String language) {
        var config = new LinkedHashMap<String, Object>();
        config.put("api_key", key);
        config.put("model", model);
        config.put("audio_format", "pcm_s16le");
        config.put("sample_rate", 24_000);
        config.put("num_channels", 1);
        if (language != null) {
            config.put("language_hints", List.of(language));
            config.put("language_hints_strict", true);
        }
        return JSON.writeValueAsString(config);
    }

    /** {@code https://api.soniox.com/v1} becomes {@code wss://stt-rt.soniox.com/transcribe-websocket}. */
    static URI realtimeUri(String baseUrl) {
        var rest = URI.create(baseUrl);
        String host = rest.getHost();
        if (host != null && host.startsWith("api.")) host = "stt-rt." + host.substring("api.".length());
        String scheme = "http".equals(rest.getScheme()) ? "ws" : "wss";
        String port = rest.getPort() == -1 ? "" : ":" + rest.getPort();
        return URI.create(scheme + "://" + host + port + "/transcribe-websocket");
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
        var frame = ByteBuffer.wrap(pcm.clone());
        sends = sends.thenCompose(ignored -> socket.sendBinary(frame, true));
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
            String finalize = JSON.writeValueAsString(Map.of("type", "finalize"));
            result = sends.thenCompose(ignored -> socket.sendText(finalize, true))
                    .thenCompose(ignored -> socket.sendBinary(ByteBuffer.allocate(0), true))
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

    private void start(URI uri, String config, Connector connector) {
        try {
            socket = connector.connect(uri, providerListener).get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            sends = CompletableFuture.completedFuture(socket);
            socket.sendText(config, true).get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
        meters.counter("memoryos.chat.voice.realtime.fallback", "provider", VoiceProvider.SONIOX.name()).increment();
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
            JsonNode event = JSON.readTree(message);
            if (!event.path("error_code").isMissingNode() && !event.path("error_code").isNull()) {
                providerFailed();
                return;
            }
            synchronized (this) {
                if (failed || closed) return;
                var pending = new StringBuilder();
                for (JsonNode token : event.path("tokens")) {
                    String text = token.path("text").asString("");
                    // <fin> answers a finalize request and <end> an endpoint; neither is spoken text.
                    if (text.isEmpty() || "<fin>".equals(text) || "<end>".equals(text)) continue;
                    if (token.path("is_final").asBoolean(false)) committed.append(text);
                    else pending.append(text);
                }
                String next = committed.toString() + pending;
                if (!next.equals(preview)) {
                    preview = next;
                    update = next.strip();
                }
                if (event.path("finished").asBoolean(false)) finalTranscript.complete(committed.toString().strip());
            }
        } catch (RuntimeException malformed) {
            providerFailed();
            return;
        }
        if (update != null && !update.isEmpty()) listener.accept(new Transcript(update, false, false));
    }

    private static CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener) {
        return HTTP.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(uri, listener);
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
            synchronized (SonioxRealtimeTranscriber.this) {
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
