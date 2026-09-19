package io.memoryos.api.chat;

import io.memoryos.BusinessException;
import io.memoryos.api.chat.contract.VoiceTicketPurpose;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.IdentityContext;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Admits a voice WebSocket only for the authenticated member holding a fresh ticket for that socket and the authority it
 * needs. Spring's default origin check keeps the handshake same-origin; the API security chain has already required a
 * session.
 */
final class VoiceHandshakeInterceptor implements HandshakeInterceptor {
    static final String ACTOR = "memoryos.voice.actor";
    static final String LANGUAGE = "memoryos.voice.language";
    private final VoiceTicketStore tickets;
    private final VoiceTicketPurpose purpose;
    private final Consumer<ActorId> requireAccess;

    VoiceHandshakeInterceptor(VoiceTicketStore tickets, VoiceTicketPurpose purpose, Consumer<ActorId> requireAccess) {
        this.tickets = tickets;
        this.purpose = purpose;
        this.requireAccess = requireAccess;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)
                || !(servlet.getPrincipal() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof IdentityContext identity)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        var query = servlet.getServletRequest();
        if (!tickets.consume(query.getParameter("ticket"), identity.actorId(), purpose)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        try {
            requireAccess.accept(identity.actorId());
        } catch (BusinessException denied) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ACTOR, identity.actorId());
        String language = query.getParameter("language");
        if (language != null) attributes.put(LANGUAGE, language);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               @Nullable Exception exception) {
    }
}
