package io.memoryos.api.chat;

import io.memoryos.BusinessException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/** JSON messages and closes shared by the voice WebSockets. Messages carry codes only, never text or provider detail. */
final class VoiceSockets {
    static final int MAX_TEXT_FRAME = 16 * 1024;
    static final ObjectMapper JSON = new ObjectMapper();

    private VoiceSockets() {}

    /** Reports a refused session with a voice code; capacity is retryable, the rest are not. */
    static void refuse(WebSocketSession socket, BusinessException refused) {
        switch (refused.code()) {
            case "CHAT_CAPACITY_EXCEEDED" -> fail(socket, "VOICE_BUSY", CloseStatus.SERVICE_OVERLOAD);
            case "CHAT_INVALID_REQUEST" -> fail(socket, "VOICE_INVALID_REQUEST", CloseStatus.POLICY_VIOLATION);
            default -> fail(socket, "VOICE_UNAVAILABLE", CloseStatus.POLICY_VIOLATION);
        }
    }

    static void fail(WebSocketSession socket, String code, CloseStatus status) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "error");
        body.put("code", code);
        write(socket, body);
        close(socket, status);
    }

    static void write(WebSocketSession socket, Map<String, Object> body) {
        if (!socket.isOpen()) return;
        try {
            socket.sendMessage(new TextMessage(JSON.writeValueAsString(body)));
        } catch (IOException | RuntimeException failed) {
            close(socket, CloseStatus.SERVER_ERROR);
        }
    }

    static void close(WebSocketSession socket, CloseStatus status) {
        if (!socket.isOpen()) return;
        try {
            socket.close(status);
        } catch (IOException ignored) {
            // The container releases the connection; afterConnectionClosed owns cleanup.
        }
    }
}
