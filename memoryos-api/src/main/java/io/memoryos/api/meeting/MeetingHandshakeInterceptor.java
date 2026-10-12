package io.memoryos.api.meeting;

import io.memoryos.BusinessException;
import io.memoryos.api.chat.VoiceTicketStore;
import io.memoryos.iam.IdentityContext;
import io.memoryos.meeting.Meeting;
import io.memoryos.meeting.MeetingService;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Admits a meeting track socket only for the owner holding a fresh ticket for that meeting and track, while the
 * meeting still records. Spring's default origin check keeps the handshake same-origin.
 */
@NullMarked
final class MeetingHandshakeInterceptor implements HandshakeInterceptor {
    static final String ACTOR = "memoryos.meeting.actor";
    static final String MEETING = "memoryos.meeting.id";
    static final String TRACK = "memoryos.meeting.track";
    static final String OFFSET = "memoryos.meeting.offset";
    private final VoiceTicketStore tickets;
    private final MeetingService meetings;

    MeetingHandshakeInterceptor(VoiceTicketStore tickets, MeetingService meetings) {
        this.tickets = tickets;
        this.meetings = meetings;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)
                || !(servlet.getPrincipal() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof IdentityContext(var actor))) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        var query = servlet.getServletRequest();
        UUID meeting;
        Meeting.Track track;
        long offset;
        try {
            meeting = UUID.fromString(query.getParameter("meeting"));
            track = Meeting.Track.valueOf(query.getParameter("track"));
            String value = query.getParameter("offset");
            offset = value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException malformed) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        if (!tickets.consume(query.getParameter("ticket"), actor, MeetingStreamWebSocketHandler.scope(meeting, track))) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        try {
            meetings.requireRecordable(actor, meeting, track);
        } catch (BusinessException denied) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ACTOR, actor);
        attributes.put(MEETING, meeting);
        attributes.put(TRACK, track);
        attributes.put(OFFSET, offset);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               @Nullable Exception exception) {
    }
}
