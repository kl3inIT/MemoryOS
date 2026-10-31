package io.memoryos.api.meeting;

import io.memoryos.BusinessException;
import io.memoryos.shared.ActorId;
import io.memoryos.meeting.Meeting;
import io.memoryos.meeting.MeetingService;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.ObjectMapper;

/**
 * Records one meeting track. The browser sends PCM16 24 kHz mono binary frames and {@code {"type":"end"}}; the server
 * sends {@code ready}, {@code preview} (uncommitted speech), {@code utterance} (stored) and {@code error} messages,
 * then {@code finished} after an end. Pausing closes the socket; resuming opens a new one at the recorded offset.
 * Audio and transcript text are never logged.
 */
@Component
@NullMarked
class MeetingStreamWebSocketHandler extends AbstractWebSocketHandler implements DisposableBean {
    static final String PATH = "/api/meeting-stream";
    static final int MAX_BINARY_FRAME = 64 * 1024;
    static final int MAX_TEXT_FRAME = 16 * 1024;
    /** A paused recording closes its socket; a live one sends audio continuously, silence included. */
    static final Duration IDLE = Duration.ofSeconds(60);
    private static final Duration WATCHDOG = Duration.ofSeconds(5);
    /** Comfortably inside the shortest proxy read timeout in front of the API. */
    private static final Duration PING = Duration.ofSeconds(20);
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MeetingService meetings;
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("meeting-websocket-watchdog").factory());
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();

    MeetingStreamWebSocketHandler(MeetingService meetings) {
        this.meetings = meetings;
    }

    /** The ticket scope binding a handshake to one meeting track. */
    static String scope(UUID meeting, Meeting.Track track) {
        return "MEETING:" + meeting + ":" + track.name();
    }

    static final class Live {
        private final WebSocketSession socket;
        private final MeetingService.TrackSession track;
        private final AtomicBoolean ending = new AtomicBoolean();
        volatile long lastAudioNanos = System.nanoTime();
        /** Counted only on the single watchdog thread. */
        private long silentChecks;
        private @Nullable ScheduledFuture<?> check;

        Live(WebSocketSession socket, MeetingService.TrackSession track) {
            this.socket = socket;
            this.track = track;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setBinaryMessageSizeLimit(MAX_BINARY_FRAME);
        session.setTextMessageSizeLimit(MAX_TEXT_FRAME);
        var socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_BYTES);
        var attributes = session.getAttributes();
        var actor = (ActorId) attributes.get(MeetingHandshakeInterceptor.ACTOR);
        var meeting = (UUID) attributes.get(MeetingHandshakeInterceptor.MEETING);
        var track = (Meeting.Track) attributes.get(MeetingHandshakeInterceptor.TRACK);
        long offset = (Long) attributes.get(MeetingHandshakeInterceptor.OFFSET);
        MeetingService.TrackSession recording;
        try {
            recording = meetings.openTrack(actor, meeting, track, offset, new MeetingService.TrackListener() {
                @Override
                public void preview(String speaker, String text) {
                    var body = message("preview");
                    body.put("track", track.name());
                    body.put("speaker", speaker);
                    body.put("text", text);
                    write(socket, body);
                }

                @Override
                public void utterance(Meeting.Utterance utterance) {
                    var body = message("utterance");
                    var value = new LinkedHashMap<String, Object>();
                    value.put("id", utterance.id().toString());
                    value.put("track", utterance.track().name());
                    value.put("speaker", utterance.speaker());
                    value.put("startMs", utterance.startMs());
                    value.put("endMs", utterance.endMs());
                    value.put("text", utterance.text());
                    value.put("confidence", utterance.confidence());
                    value.put("spans", utterance.spans().stream().map(span -> {
                        var marked = new LinkedHashMap<String, Object>();
                        marked.put("start", span.start());
                        marked.put("end", span.end());
                        marked.put("confidence", span.confidence());
                        return marked;
                    }).toList());
                    body.put("utterance", value);
                    write(socket, body);
                }

                @Override
                public void failed() {
                    fail(socket, "MEETING_PROVIDER_FAILED", CloseStatus.SERVER_ERROR);
                }
            });
        } catch (BusinessException refused) {
            fail(socket, refused.code().startsWith("MEETING_") ? refused.code()
                    : "CHAT_CAPACITY_EXCEEDED".equals(refused.code()) ? "MEETING_BUSY" : "MEETING_UNAVAILABLE",
                    CloseStatus.POLICY_VIOLATION);
            return;
        }
        var live = new Live(socket, recording);
        sessions.put(session.getId(), live);
        write(socket, message("ready"));
        live.check = watchdog.scheduleWithFixedDelay(() -> expire(live), WATCHDOG.toSeconds(),
                WATCHDOG.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var live = sessions.get(session.getId());
        if (live == null || live.ending.get()) return;
        var payload = message.getPayload();
        if (payload.remaining() % 2 != 0) {
            fail(live.socket, "MEETING_INVALID_AUDIO", CloseStatus.POLICY_VIOLATION);
            return;
        }
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        try {
            live.track.append(pcm);
        } catch (BusinessException limit) {
            fail(live.socket, limit.code(), CloseStatus.POLICY_VIOLATION);
            return;
        } catch (IllegalStateException closed) {
            return;
        }
        live.lastAudioNanos = System.nanoTime();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var live = sessions.get(session.getId());
        if (live == null) return;
        String type;
        try {
            type = JSON.readTree(message.getPayload()).path("type").asString("");
        } catch (RuntimeException malformed) {
            type = "";
        }
        if (!"end".equals(type)) {
            fail(live.socket, "MEETING_INVALID_MESSAGE", CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!live.ending.compareAndSet(false, true)) return;
        live.track.finish().whenComplete((_, _) -> {
            write(live.socket, message("finished"));
            close(live.socket, CloseStatus.NORMAL);
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        close(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var live = sessions.remove(session.getId());
        if (live == null) return;
        if (live.check != null) live.check.cancel(false);
        live.track.close();
    }

    @Override
    public void destroy() {
        watchdog.shutdownNow();
        sessions.values().forEach(live -> close(live.socket, CloseStatus.GOING_AWAY));
    }

    void expire(Live live) {
        if (live.ending.get()) return;
        if (System.nanoTime() - live.lastAudioNanos > IDLE.toNanos()) {
            fail(live.socket, "MEETING_IDLE", CloseStatus.POLICY_VIOLATION);
            return;
        }
        // Nobody speaking means the server writes nothing for minutes while the browser keeps sending audio. A
        // reverse proxy reads that as an idle upstream and cuts the connection — 300s at the staging edge — so the
        // recording stalls in a quiet room. A ping every PING keeps every hop in between awake; the browser answers
        // it without the page being told.
        if (++live.silentChecks % (PING.toSeconds() / WATCHDOG.toSeconds()) == 0) ping(live.socket);
    }

    private static void ping(WebSocketSession socket) {
        if (!socket.isOpen()) return;
        try {
            socket.sendMessage(new PingMessage());
        } catch (IOException | RuntimeException failed) {
            close(socket, CloseStatus.SERVER_ERROR);
        }
    }

    private static LinkedHashMap<String, Object> message(String type) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", type);
        return body;
    }

    private static void fail(WebSocketSession socket, String code, CloseStatus status) {
        var body = message("error");
        body.put("code", code);
        write(socket, body);
        close(socket, status);
    }

    private static void write(WebSocketSession socket, Map<String, Object> body) {
        if (!socket.isOpen()) return;
        try {
            socket.sendMessage(new TextMessage(JSON.writeValueAsString(body)));
        } catch (IOException | RuntimeException failed) {
            close(socket, CloseStatus.SERVER_ERROR);
        }
    }

    private static void close(WebSocketSession socket, CloseStatus status) {
        if (!socket.isOpen()) return;
        try {
            socket.close(status);
        } catch (IOException ignored) {
            // The container releases the connection; afterConnectionClosed owns cleanup.
        }
    }
}
