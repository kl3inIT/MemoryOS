package io.memoryos.api.chat;

import io.memoryos.chat.voice.VoiceTranscriptionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class VoiceWebSocketConfiguration implements WebSocketConfigurer {
    private final TranscribeWebSocketHandler transcribe;
    private final VoiceTicketStore tickets;
    private final VoiceTranscriptionService transcription;

    VoiceWebSocketConfiguration(TranscribeWebSocketHandler transcribe, VoiceTicketStore tickets,
                                VoiceTranscriptionService transcription) {
        this.transcribe = transcribe;
        this.tickets = tickets;
        this.transcription = transcription;
    }

    /** No allowed origins are configured, so Spring accepts same-origin handshakes only. */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(transcribe, TranscribeWebSocketHandler.PATH)
                .addInterceptors(new VoiceHandshakeInterceptor(tickets, transcription));
    }
}
