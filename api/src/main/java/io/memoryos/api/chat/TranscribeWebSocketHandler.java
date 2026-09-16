package io.memoryos.api.chat;

import io.memoryos.BusinessException;
import io.memoryos.chat.voice.ChunkedTranscriber;
import io.memoryos.chat.voice.Transcript;
import io.memoryos.chat.voice.VoiceTranscriptionService;
import io.memoryos.iam.identity.ActorId;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Voice transcription WebSocket (Onyx /voice/transcribe/stream parity). The browser sends PCM16 24 kHz mono binary
 * frames and {@code {"type":"end"}}; the server sends {@code transcript} and {@code error} messages. Onyx has no idle
 * or duration bound, so this socket adds both. Transcripts and audio are never logged.
 */
@Component
class TranscribeWebSocketHandler extends AbstractWebSocketHandler implements DisposableBean {
    static final String PATH = "/api/chat/voice/transcribe/stream";
    static final int MAX_BINARY_FRAME = 64 * 1024;
    static final Duration IDLE = Duration.ofSeconds(60);
    static final Duration MAX_SESSION = Duration.ofMinutes(10);
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_BYTES = 256 * 1024;
    private final VoiceTranscriptionService transcription;
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("voice-websocket-watchdog").factory());
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();

    TranscribeWebSocketHandler(VoiceTranscriptionService transcription) {
        this.transcription = transcription;
    }

    private static final class Live {
        private final WebSocketSession socket;
        private final ChunkedTranscriber transcriber;
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean ending = new AtomicBoolean();
        private volatile long lastAudioNanos = startedNanos;
        private long bytes;
        private @Nullable ScheduledFuture<?> check;

        private Live(WebSocketSession socket, ChunkedTranscriber transcriber) {
            this.socket = socket;
            this.transcriber = transcriber;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Frames above these sizes are rejected by the container before they reach the handler.
        session.setBinaryMessageSizeLimit(MAX_BINARY_FRAME);
        session.setTextMessageSizeLimit(VoiceSockets.MAX_TEXT_FRAME);
        var socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_BYTES);
        var actor = (ActorId) session.getAttributes().get(VoiceHandshakeInterceptor.ACTOR);
        var language = (String) session.getAttributes().get(VoiceHandshakeInterceptor.LANGUAGE);
        ChunkedTranscriber transcriber;
        try {
            transcriber = transcription.open(actor, language, transcript -> send(socket, transcript));
        } catch (BusinessException refused) {
            VoiceSockets.refuse(socket, refused);
            return;
        }
        var live = new Live(socket, transcriber);
        sessions.put(session.getId(), live);
        live.check = watchdog.scheduleWithFixedDelay(() -> expire(live), 5, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var live = sessions.get(session.getId());
        if (live == null || live.ending.get()) return;
        var payload = message.getPayload();
        int size = payload.remaining();
        if (size % 2 != 0) {
            VoiceSockets.fail(live.socket, "VOICE_INVALID_AUDIO", CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (live.bytes + size > VoiceTranscriptionService.MAX_RECORDING_BYTES) {
            VoiceSockets.fail(live.socket, "VOICE_AUDIO_TOO_LARGE", CloseStatus.POLICY_VIOLATION);
            return;
        }
        live.bytes += size;
        byte[] pcm = new byte[size];
        payload.get(pcm);
        live.transcriber.append(pcm);
        live.lastAudioNanos = System.nanoTime();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var live = sessions.get(session.getId());
        if (live == null) return;
        String type;
        try {
            type = VoiceSockets.JSON.readTree(message.getPayload()).path("type").asString("");
        } catch (RuntimeException malformed) {
            type = "";
        }
        if (!"end".equals(type)) {
            VoiceSockets.fail(live.socket, "VOICE_INVALID_MESSAGE", CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!live.ending.compareAndSet(false, true)) return;
        live.transcriber.finish().whenComplete((text, failure) -> {
            if (failure != null) {
                VoiceSockets.fail(live.socket, "VOICE_PROVIDER_FAILED", CloseStatus.SERVER_ERROR);
                return;
            }
            send(live.socket, new Transcript(text, true));
            VoiceSockets.close(live.socket, CloseStatus.NORMAL);
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        VoiceSockets.close(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var live = sessions.remove(session.getId());
        if (live == null) return;
        if (live.check != null) live.check.cancel(false);
        live.transcriber.close();
    }

    @Override
    public void destroy() {
        watchdog.shutdownNow();
        sessions.values().forEach(live -> VoiceSockets.close(live.socket, CloseStatus.GOING_AWAY));
    }

    private void expire(Live live) {
        long now = System.nanoTime();
        if (now - live.startedNanos > MAX_SESSION.toNanos())
            VoiceSockets.fail(live.socket, "VOICE_SESSION_TOO_LONG", CloseStatus.POLICY_VIOLATION);
        else if (!live.ending.get() && now - live.lastAudioNanos > IDLE.toNanos())
            VoiceSockets.fail(live.socket, "VOICE_IDLE", CloseStatus.POLICY_VIOLATION);
    }

    private static void send(WebSocketSession socket, Transcript transcript) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "transcript");
        body.put("text", transcript.text());
        body.put("isFinal", transcript.isFinal());
        VoiceSockets.write(socket, body);
    }
}
