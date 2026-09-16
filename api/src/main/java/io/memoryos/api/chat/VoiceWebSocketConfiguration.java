package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.VoiceTicketPurpose;
import io.memoryos.chat.voice.VoiceSynthesisService;
import io.memoryos.chat.voice.VoiceTranscriptionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class VoiceWebSocketConfiguration implements WebSocketConfigurer {
    private final TranscribeWebSocketHandler transcribe;
    private final SynthesizeWebSocketHandler synthesize;
    private final VoiceTicketStore tickets;
    private final VoiceTranscriptionService transcription;
    private final VoiceSynthesisService synthesis;

    VoiceWebSocketConfiguration(TranscribeWebSocketHandler transcribe, SynthesizeWebSocketHandler synthesize,
                                VoiceTicketStore tickets, VoiceTranscriptionService transcription,
                                VoiceSynthesisService synthesis) {
        this.transcribe = transcribe;
        this.synthesize = synthesize;
        this.tickets = tickets;
        this.transcription = transcription;
        this.synthesis = synthesis;
    }

    /** No allowed origins are configured, so Spring accepts same-origin handshakes only. */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(transcribe, TranscribeWebSocketHandler.PATH)
                .addInterceptors(new VoiceHandshakeInterceptor(tickets, VoiceTicketPurpose.TRANSCRIBE, transcription::requireAccess));
        registry.addHandler(synthesize, SynthesizeWebSocketHandler.PATH)
                .addInterceptors(new VoiceHandshakeInterceptor(tickets, VoiceTicketPurpose.SYNTHESIZE, synthesis::requireAccess));
    }
}
