package io.memoryos.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Soniox realtime transcription for long recordings, following Anarlog's listener: speaker diarization, endpoint
 * detection and context terms in the first message; final tokens grouped into segments that end at a speaker change
 * or an {@code <end>} endpoint; a keepalive after five seconds without audio; and, when the provider stream fails,
 * a reconnect with 2/5/10/20/30-second backoff that replays the last five seconds of audio and shifts the new
 * stream's times so the recording clock stays continuous. Audio and text are never logged.
 */
final class SonioxLiveTranscription implements LiveTranscription {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SonioxLiveTranscription.class);
    static final Duration REPLAY = Duration.ofSeconds(5);
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(20), Duration.ofSeconds(30));
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration KEEPALIVE_AFTER = Duration.ofSeconds(5);
    private static final Duration FINISH_TIMEOUT = Duration.ofSeconds(15);
    private static final int REPLAY_BYTES = (int) (Pcm16.BYTES_PER_SECOND * REPLAY.toSeconds());
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ScheduledExecutorService TIMERS =
            Executors.newScheduledThreadPool(1, Thread.ofPlatform().daemon().name("voice-live-timer").factory());
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    interface Connector {
        CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener);
    }

    private final URI uri;
    private final long offsetMs;
    private final String config;
    private final Connector connector;
    private final Listener listener;
    private final List<Duration> backoff;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final ArrayDeque<byte[]> retained = new ArrayDeque<>();
    private final ScheduledFuture<?> keepalive;
    private @Nullable WebSocket socket;
    private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);
    private int retainedBytes;
    private long sentBytes;
    private long streamBaseMs;
    private long lastSendNanos = System.nanoTime();
    private int attempt;
    private int generation;
    private @Nullable Current current;
    private boolean finishing;
    private boolean closed;

    /** The segment being assembled from final tokens of one speaker. */
    private static final class Current {
        private final String speaker;
        private final SpokenText text = new SpokenText();
        private final long startMs;
        private long endMs;

        private Current(String speaker, long startMs) {
            this.speaker = speaker;
            this.startMs = startMs;
            this.endMs = startMs;
        }
    }

    static SonioxLiveTranscription open(String baseUrl, String key, String model, Options options, long offsetMs,
                                        Listener listener) {
        return open(baseUrl, key, model, options, offsetMs, listener, SonioxLiveTranscription::connect, BACKOFF);
    }

    static SonioxLiveTranscription open(String baseUrl, String key, String model, Options options, long offsetMs,
                                        Listener listener, Connector connector, List<Duration> backoff) {
        var stream = new SonioxLiveTranscription(SonioxRealtimeTranscriber.realtimeUri(baseUrl),
                config(key, model, options), connector, listener, offsetMs, backoff);
        try {
            var opened = stream.connect(0);
            synchronized (stream) {
                stream.socket = opened;
            }
        } catch (RuntimeException unavailable) {
            stream.close();
            throw unavailable;
        }
        return stream;
    }

    private SonioxLiveTranscription(URI uri, String config, Connector connector, Listener listener, long offsetMs,
                                    List<Duration> backoff) {
        this.uri = uri;
        this.config = config;
        this.connector = connector;
        this.listener = listener;
        this.offsetMs = offsetMs;
        this.streamBaseMs = offsetMs;
        this.backoff = backoff;
        this.keepalive = TIMERS.scheduleWithFixedDelay(this::keepalive, 1, 1, TimeUnit.SECONDS);
    }

    /** Anarlog's Soniox configuration: PCM16 24 kHz mono, diarization and endpoints, domain terms as context. */
    static String config(String key, String model, Options options) {
        var config = new LinkedHashMap<String, Object>();
        config.put("api_key", key);
        config.put("model", model);
        config.put("audio_format", "pcm_s16le");
        config.put("sample_rate", Pcm16.SAMPLE_RATE);
        config.put("num_channels", 1);
        if (options.language() != null) {
            config.put("language_hints", List.of(options.language()));
            config.put("language_hints_strict", true);
        }
        config.put("enable_endpoint_detection", true);
        config.put("enable_speaker_diarization", options.diarize());
        if (!options.terms().isEmpty()) config.put("context", Map.of("terms", options.terms()));
        return JSON.writeValueAsString(config);
    }

    @Override
    public synchronized void append(byte[] pcm) {
        if (closed || finishing) throw new IllegalStateException("Transcription no longer accepts audio");
        if (pcm.length % 2 != 0) throw new IllegalArgumentException("PCM16 audio must contain whole samples");
        byte[] frame = pcm.clone();
        retained.addLast(frame);
        retainedBytes += frame.length;
        while (retainedBytes - retained.peekFirst().length >= REPLAY_BYTES) retainedBytes -= retained.removeFirst().length;
        sentBytes += frame.length;
        // While reconnecting, only the retained tail survives; it is replayed into the new stream.
        if (socket != null) send(frame);
    }

    @Override
    public CompletableFuture<Void> finish() {
        synchronized (this) {
            if (closed) return CompletableFuture.completedFuture(null);
            if (finishing) return finished;
            finishing = true;
            var live = socket;
            if (live == null) {
                flush();
                finished.complete(null);
                return finished;
            }
            String finalize = JSON.writeValueAsString(Map.of("type", "finalize"));
            sends = sends.thenCompose(ignored -> live.sendText(finalize, true))
                    .thenCompose(ignored -> live.sendBinary(ByteBuffer.allocate(0), true));
        }
        return finished.orTimeout(FINISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(timeout -> {
                    synchronized (this) {
                        flush();
                    }
                    return null;
                });
    }

    @Override
    public void close() {
        WebSocket live;
        synchronized (this) {
            if (closed) return;
            closed = true;
            live = socket;
            socket = null;
            retained.clear();
            retainedBytes = 0;
            flush();
        }
        keepalive.cancel(false);
        finished.complete(null);
        if (live != null && !live.isOutputClosed()) live.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    /** Opens a provider stream and sends the configuration; the caller installs the socket under the monitor. */
    private WebSocket connect(int currentGeneration) {
        WebSocket opened;
        try {
            opened = connector.connect(uri, new ProviderListener(currentGeneration))
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            opened.sendText(config, true).get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Live transcription unavailable", failed);
        }
        return opened;
    }

    /** Caller holds the monitor. */
    private void send(byte[] frame) {
        var live = socket;
        int sentGeneration = generation;
        lastSendNanos = System.nanoTime();
        sends = sends.thenCompose(ignored -> live.sendBinary(ByteBuffer.wrap(frame), true));
        sends.whenComplete((ignored, failure) -> {
            if (failure != null) providerFailed(sentGeneration, "audio send failed");
        });
    }

    private void keepalive() {
        synchronized (this) {
            if (closed || finishing || socket == null) return;
            if (System.nanoTime() - lastSendNanos < KEEPALIVE_AFTER.toNanos()) return;
            var live = socket;
            int sentGeneration = generation;
            lastSendNanos = System.nanoTime();
            String message = JSON.writeValueAsString(Map.of("type", "keepalive"));
            sends = sends.thenCompose(ignored -> live.sendText(message, true));
            sends.whenComplete((ignored, failure) -> {
                if (failure != null) providerFailed(sentGeneration, "keepalive failed");
            });
        }
    }

    private void providerFailed(int failedGeneration, String reason) {
        WebSocket broken;
        synchronized (this) {
            if (closed || failedGeneration != generation || socket == null) return;
            broken = socket;
            socket = null;
            generation++;
            flush();
            listener.preview("", "");
            if (finishing) {
                finished.complete(null);
            } else if (attempt >= backoff.size()) {
                LOG.warn("Soniox stream gave up after {} attempts ({})", attempt, reason);
                listener.failed();
            } else {
                LOG.warn("Soniox stream failed ({}); reconnecting, attempt {}", reason, attempt + 1);
                int reconnectGeneration = generation;
                TIMERS.schedule(() -> reconnect(reconnectGeneration), backoff.get(attempt++).toMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }
        if (!broken.isOutputClosed()) broken.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
    }

    private void reconnect(int reconnectGeneration) {
        synchronized (this) {
            if (closed || finishing || reconnectGeneration != generation) return;
        }
        WebSocket opened;
        try {
            opened = connect(reconnectGeneration);
        } catch (RuntimeException unavailable) {
            synchronized (this) {
                if (closed || finishing || reconnectGeneration != generation) return;
                if (attempt >= backoff.size()) {
                    listener.failed();
                    return;
                }
                TIMERS.schedule(() -> reconnect(reconnectGeneration), backoff.get(attempt++).toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            return;
        }
        synchronized (this) {
            if (closed || finishing || reconnectGeneration != generation) {
                opened.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                return;
            }
            // Anarlog's replay offset: the new stream starts at the replayed tail, not at the recording start.
            socket = opened;
            sends = CompletableFuture.completedFuture(null);
            streamBaseMs = offsetMs + toMillis(sentBytes - retainedBytes);
            for (byte[] frame : retained) send(frame);
        }
    }

    /** Caller holds the monitor. Emits the segment being assembled, if any. */
    private void flush() {
        var segment = current;
        current = null;
        if (segment == null) return;
        if (segment.text.isEmpty()) return;
        listener.segment(new Segment(segment.speaker, segment.startMs, segment.endMs, segment.text.said(),
                segment.text.confidence(), segment.text.spans()));
    }

    private void providerMessage(int messageGeneration, String message) {
        JsonNode event;
        try {
            event = JSON.readTree(message);
        } catch (RuntimeException malformed) {
            providerFailed(messageGeneration, "unreadable message");
            return;
        }
        var code = event.path("error_code");
        if (!code.isMissingNode() && !code.isNull()) {
            // The code and the type say what to fix; the provider's own prose may carry account detail, so it stays out.
            providerFailed(messageGeneration,
                    "provider error " + code.asString("") + " " + event.path("error_type").asString(""));
            return;
        }
        synchronized (this) {
            if (closed || messageGeneration != generation) return;
            var pending = new StringBuilder();
            String pendingSpeaker = null;
            for (JsonNode token : event.path("tokens")) {
                String text = token.path("text").asString("");
                if ("<end>".equals(text)) {
                    flush();
                    continue;
                }
                if (text.isEmpty() || "<fin>".equals(text)) continue;
                String speaker = token.path("speaker").asString("1");
                if (speaker.isEmpty()) speaker = "1";
                if (!token.path("is_final").asBoolean(false)) {
                    if (pendingSpeaker == null) pendingSpeaker = speaker;
                    pending.append(text);
                    continue;
                }
                long start = streamBaseMs + token.path("start_ms").asLong(0);
                long end = streamBaseMs + token.path("end_ms").asLong(0);
                if (current != null && !current.speaker.equals(speaker)) flush();
                if (current == null) current = new Current(speaker, start);
                current.text.append(text, token.path("confidence").asDouble(1.0));
                current.endMs = Math.max(current.endMs, end);
            }
            String previewSpeaker = current != null ? current.speaker : pendingSpeaker;
            String preview = ((current != null ? current.text.toString() : "") + pending).strip();
            listener.preview(previewSpeaker == null ? "" : previewSpeaker, preview);
            if (event.path("finished").asBoolean(false)) {
                flush();
                finished.complete(null);
            }
        }
    }

    private static long toMillis(long bytes) {
        return bytes * 1000 / Pcm16.BYTES_PER_SECOND;
    }

    private static CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener) {
        return HTTP.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(uri, listener);
    }

    private final class ProviderListener implements WebSocket.Listener {
        private final int listenerGeneration;
        private final StringBuilder message = new StringBuilder();

        private ProviderListener(int listenerGeneration) {
            this.listenerGeneration = listenerGeneration;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String complete = null;
            synchronized (message) {
                message.append(data);
                if (last) {
                    complete = message.toString();
                    message.setLength(0);
                }
            }
            if (complete != null) providerMessage(listenerGeneration, complete);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (SonioxLiveTranscription.this) {
                if (finishing) {
                    flush();
                    finished.complete(null);
                    return CompletableFuture.completedFuture(null);
                }
            }
            providerFailed(listenerGeneration, "closed by provider, status " + statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            providerFailed(listenerGeneration, error.getClass().getSimpleName());
        }
    }
}
