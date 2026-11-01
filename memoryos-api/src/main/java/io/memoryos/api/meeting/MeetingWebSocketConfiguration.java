package io.memoryos.api.meeting;

import io.memoryos.api.chat.VoiceTicketStore;
import io.memoryos.meeting.MeetingService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
class MeetingWebSocketConfiguration implements WebSocketConfigurer {
    private final MeetingStreamWebSocketHandler stream;
    private final VoiceTicketStore tickets;
    private final MeetingService meetings;

    MeetingWebSocketConfiguration(MeetingStreamWebSocketHandler stream, VoiceTicketStore tickets, MeetingService meetings) {
        this.stream = stream;
        this.tickets = tickets;
        this.meetings = meetings;
    }

    /** No allowed origins are configured, so Spring accepts same-origin handshakes only. */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(stream, MeetingStreamWebSocketHandler.PATH)
                .addInterceptors(new MeetingHandshakeInterceptor(tickets, meetings));
    }
}
