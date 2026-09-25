package io.memoryos.api.chat;

import io.memoryos.BusinessException;
import io.memoryos.voice.StreamingSynthesizer;
import io.memoryos.voice.VoiceSynthesisService;
import io.memoryos.shared.ActorId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.JsonNode;

/**
 * Streaming read-aloud WebSocket for Auto-Playback (Onyx /voice/synthesize/stream parity). The browser sends an optional
 * {@code {"type":"config","speed"}} first, then {@code synthesize} messages as the answer grows and {@code end}; the
 * server sends MP3 binary frames, {@code audio_done} and coded {@code error} messages. Text is never logged.
 */
@Component
class SynthesizeWebSocketHandler extends AbstractWebSocketHandler implements DisposableBean {
    static final String PATH = "/api/chat/voice/synthesize/stream";
    /** An answer can pause while tools run, so the idle bound is longer than for dictation. */
    static final Duration IDLE = Duration.ofMinutes(5);
    static final Duration MAX_SESSION = Duration.ofMinutes(10);
    private static final double DEFAULT_SPEED = 1.0;
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_BYTES = 1024 * 1024;
    private final VoiceSynthesisService synthesis;
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("voice-synthesis-watchdog").factory());
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();

    SynthesizeWebSocketHandler(VoiceSynthesisService synthesis) {
        this.synthesis = synthesis;
    }

    private static final class Live {
        private final WebSocketSession socket;
        private final ActorId actor;
        private final long startedNanos = System.nanoTime();
        private volatile long lastMessageNanos = startedNanos;
        private volatile @Nullable StreamingSynthesizer synthesizer;
        private volatile boolean ending;
        private @Nullable ScheduledFuture<?> check;

        private Live(WebSocketSession socket, ActorId actor) {
            this.socket = socket;
            this.actor = actor;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(VoiceSockets.MAX_TEXT_FRAME);
        var socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_BYTES);
        var live = new Live(socket, (ActorId) session.getAttributes().get(VoiceHandshakeInterceptor.ACTOR));
        sessions.put(session.getId(), live);
        live.check = watchdog.scheduleWithFixedDelay(() -> expire(live), 5, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var live = sessions.get(session.getId());
        if (live == null) return;
        live.lastMessageNanos = System.nanoTime();
        JsonNode body;
        try {
            body = VoiceSockets.JSON.readTree(message.getPayload());
        } catch (RuntimeException malformed) {
            invalid(live);
            return;
        }
        switch (body.path("type").asString("")) {
            case "config" -> configure(live, body.path("speed"));
            case "synthesize" -> synthesize(live, body.path("text"));
            case "end" -> end(live);
            default -> invalid(live);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var live = sessions.get(session.getId());
        if (live != null) invalid(live);
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
        var synthesizer = live.synthesizer;
        if (synthesizer != null) synthesizer.close();
    }

    @Override
    public void destroy() {
        watchdog.shutdownNow();
        sessions.values().forEach(live -> VoiceSockets.close(live.socket, CloseStatus.GOING_AWAY));
    }

    private void configure(Live live, JsonNode speed) {
        if (live.synthesizer != null || live.ending || !speed.isNumber()) {
            invalid(live);
            return;
        }
        open(live, speed.asDouble());
    }

    private void synthesize(Live live, JsonNode text) {
        if (live.ending || !text.isString()) {
            invalid(live);
            return;
        }
        if (live.synthesizer == null && !open(live, DEFAULT_SPEED)) return;
        try {
            live.synthesizer.append(text.asString());
        } catch (BusinessException tooLong) {
            VoiceSockets.fail(live.socket, "VOICE_TEXT_TOO_LONG", CloseStatus.POLICY_VIOLATION);
        } catch (IllegalStateException closed) {
            invalid(live);
        }
    }

    private void end(Live live) {
        if (live.ending) return;
        live.ending = true;
        var synthesizer = live.synthesizer;
        if (synthesizer == null) {
            done(live.socket);
            return;
        }
        synthesizer.finish().whenComplete((ignored, failure) -> {
            if (failure != null) VoiceSockets.fail(live.socket, "VOICE_PROVIDER_FAILED", CloseStatus.SERVER_ERROR);
            else done(live.socket);
        });
    }

    private boolean open(Live live, double speed) {
        try {
            live.synthesizer = synthesis.openStreaming(live.actor, speed, audio -> sendAudio(live.socket, audio));
            return true;
        } catch (BusinessException refused) {
            VoiceSockets.refuse(live.socket, refused);
            return false;
        }
    }

    private void expire(Live live) {
        long now = System.nanoTime();
        if (now - live.startedNanos > MAX_SESSION.toNanos())
            VoiceSockets.fail(live.socket, "VOICE_SESSION_TOO_LONG", CloseStatus.POLICY_VIOLATION);
        else if (!live.ending && now - live.lastMessageNanos > IDLE.toNanos())
            VoiceSockets.fail(live.socket, "VOICE_IDLE", CloseStatus.POLICY_VIOLATION);
    }

    private static void invalid(Live live) {
        VoiceSockets.fail(live.socket, "VOICE_INVALID_MESSAGE", CloseStatus.POLICY_VIOLATION);
    }

    private static void done(WebSocketSession socket) {
        VoiceSockets.write(socket, Map.of("type", "audio_done"));
        VoiceSockets.close(socket, CloseStatus.NORMAL);
    }

    /** A closed or saturated socket stops the speech; the synthesizer reports it and the close cleans up. */
    private static void sendAudio(WebSocketSession socket, byte[] audio) {
        if (!socket.isOpen()) throw new IllegalStateException("Voice socket closed");
        try {
            socket.sendMessage(new BinaryMessage(audio));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
