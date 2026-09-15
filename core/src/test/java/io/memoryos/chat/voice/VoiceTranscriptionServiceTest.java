package io.memoryos.chat.voice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.chat.ChatException;
import io.memoryos.iam.group.IamAuthorization;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceTranscriptionServiceTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final VoiceTranscriptionService service = new VoiceTranscriptionService(
            mock(VoiceConnectionService.class), mock(IamAuthorization.class), meters);
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            byte[] response = (status == 200 ? "{\"text\":\"xin chào\"}" : "{\"error\":\"voice-provider-diagnostic\"}").getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private VoiceConnectionService.Connection connection() {
        return new VoiceConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), VoiceProvider.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "whisper-1", "", "", null, 1);
    }

    @Test
    void uploadsNamedWavWithConfiguredModelAndLanguageThroughTheOpenAiProtocol() {
        byte[] pcm = Pcm16Test.tone(0.5, 3000);
        String text = service.transcribe(connection(), "voice-secret", "vi", Pcm16.wav(pcm, 0, pcm.length));
        assertEquals("xin chào", text);
        assertEquals("Bearer voice-secret", authorization.get());
        assertTrue(body.get().contains("filename=\"audio.wav\""));
        assertTrue(body.get().matches("(?s).*name=\"model\".*whisper-1.*"));
        assertTrue(body.get().matches("(?s).*name=\"language\".*vi.*"));
        assertEquals(1, meters.get("memoryos.chat.voice.request").tag("operation", "transcribe").tag("outcome", "succeeded").timer().count());
    }

    @Test
    void providerFailureIsReportedWithoutItsPayload() {
        status = 500;
        byte[] pcm = Pcm16Test.tone(0.5, 3000);
        var failure = assertThrows(ChatException.class,
                () -> service.transcribe(connection(), "", "en", Pcm16.wav(pcm, 0, pcm.length)));
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("diagnostic"));
    }
}
