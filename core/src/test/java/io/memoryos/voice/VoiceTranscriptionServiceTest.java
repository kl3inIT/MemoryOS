package io.memoryos.voice;

import io.memoryos.shared.ActorId;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
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
    void aClosedSessionAddsItsRecordedSecondsToAiUsageOnce() {
        var connections = mock(VoiceConnectionService.class);
        var recorder = mock(io.memoryos.usage.AiUsageRecorder.class);
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("usage", recorder));
        var metered = new VoiceTranscriptionService(connections, mock(IamAuthorization.class), meters,
                factory.getBeanProvider(io.memoryos.usage.AiUsageRecorder.class));
        var actor = new ActorId(UUID.randomUUID());
        var connection = connection();
        org.mockito.Mockito.when(connections.resolve(actor)).thenReturn(new VoiceConnectionService.Access(connection, null));
        org.mockito.Mockito.when(connections.key(connection)).thenReturn("voice-secret");
        var session = metered.open(actor, "vi", ignored -> {});
        session.append(new byte[48_000]);
        session.append(new byte[24_000]);
        session.close();
        session.close();
        var captured = org.mockito.ArgumentCaptor.forClass(io.memoryos.usage.AiUsage.class);
        org.mockito.Mockito.verify(recorder).record(captured.capture());
        assertEquals(1.5, captured.getValue().audioSeconds(), 1e-9);
        assertEquals(io.memoryos.usage.AiUsageFlow.SPEECH_TO_TEXT, captured.getValue().flow());
        assertEquals("whisper-1", captured.getValue().modelName());
        assertEquals(actor.value(), captured.getValue().actor());
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
    void azureRecognitionReceives16kHzAudioWithoutTheUploadWavHeader() {
        var upload = new AtomicReference<byte[]>();
        server.createContext(AzureSpeech.STT_PATH, exchange -> {
            upload.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"RecognitionStatus\":\"Success\",\"DisplayText\":\"Xin chào.\"}".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        byte[] pcm = Pcm16Test.tone(0.3, 3000);
        var azure = new VoiceConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), VoiceProvider.AZURE,
                "http://127.0.0.1:" + server.getAddress().getPort(), "default", "", "", null, 1);
        assertEquals("Xin chào.", service.transcribe(azure, "azure-secret", "vi", Pcm16.wav(pcm, 0, pcm.length)));
        assertEquals(Pcm16.WAV_HEADER_BYTES + pcm.length * 2 / 3, upload.get().length);
    }

    @Test
    void providerFailureIsReportedWithoutItsPayload() {
        status = 500;
        byte[] pcm = Pcm16Test.tone(0.5, 3000);
        var failure = assertThrows(VoiceException.class,
                () -> service.transcribe(connection(), "", "en", Pcm16.wav(pcm, 0, pcm.length)));
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("diagnostic"));
    }
}
