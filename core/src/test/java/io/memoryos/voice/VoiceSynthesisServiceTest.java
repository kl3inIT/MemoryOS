package io.memoryos.voice;

import io.memoryos.shared.ActorId;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.iam.IamAuthorization;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VoiceSynthesisServiceTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final VoiceSynthesisService service = new VoiceSynthesisService(
            mock(VoiceConnectionService.class), mock(IamAuthorization.class), meters);
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger released = new AtomicInteger();
    private HttpServer server;
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/audio/speech", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            byte[] response = (status == 200 ? "mp3-" + bodies.size() + ";" : "{\"error\":\"voice-provider-diagnostic\"}").getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "audio/mpeg" : "application/json");
            exchange.sendResponseHeaders(status, 0);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aKeyThatCannotBeReadDoesNotKeepAStreamSlot() {
        var connections = mock(VoiceConnectionService.class);
        var reader = new VoiceSynthesisService(connections, mock(IamAuthorization.class), meters);
        var actor = new ActorId(UUID.randomUUID());
        var tts = connection();
        Mockito.when(connections.resolve(actor)).thenReturn(new VoiceConnectionService.Access(null, tts));
        Mockito.when(connections.key(tts)).thenThrow(VoiceException.providerUnavailable());

        // Twice the slot count: a leaked slot would turn the later calls into "busy".
        for (int attempt = 0; attempt < 16; attempt++) {
            var failure = assertThrows(VoiceException.class, () -> reader.open(actor, "Xin chào", 1.0));
            assertEquals(VoiceException.providerUnavailable().code(), failure.code());
            var streaming = assertThrows(VoiceException.class, () -> reader.openStreaming(actor, 1.0, _ -> { }));
            assertEquals(VoiceException.providerUnavailable().code(), streaming.code());
        }
    }

    private VoiceConnectionService.Connection connection() {
        return new VoiceConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), VoiceProvider.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", "tts-1", "alloy", null, 1);
    }

    @Test
    void splitsAtSentenceEndsThenWhitespaceWithoutBreakingSurrogatePairs() {
        assertEquals(List.of("Câu một.", "Câu hai!"), VoiceSynthesisService.segments("Câu một. Câu hai!", 10));
        assertEquals(List.of("alpha beta", "gamma"), VoiceSynthesisService.segments("alpha beta gamma", 12));
        assertEquals(List.of("abcde", "fghij"), VoiceSynthesisService.segments("abcdefghij", 5));
        assertEquals(List.of("ab", "😀c", "d"), VoiceSynthesisService.segments("ab😀cd", 3));
        assertEquals(List.of("dòng một", "dòng hai"), VoiceSynthesisService.segments("dòng một\ndòng hai", 12));
        assertEquals(List.of("xin chào"), VoiceSynthesisService.segments("  xin chào  ", VoiceSynthesisService.MAX_SEGMENT_LENGTH));
    }

    @Test
    void streamsEverySegmentInOrderWithTheConfiguredModelVoiceAndSpeed() throws IOException {
        var output = new ByteArrayOutputStream();
        try (var speech = service.stream(connection(), "voice-secret", List.of("Một.", "Hai."), 1.3, released::incrementAndGet)) {
            assertEquals(1, bodies.size(), "the first segment is requested before the response starts");
            speech.writeTo(output);
        }
        assertEquals("mp3-1;mp3-2;", output.toString(UTF_8));
        assertEquals(1, released.get());
        assertEquals("Bearer voice-secret", authorization.get());
        assertTrue(bodies.get(0).contains("\"input\":\"Một.\""));
        assertTrue(bodies.get(1).contains("\"input\":\"Hai.\""));
        for (String body : bodies) {
            assertTrue(body.contains("\"model\":\"tts-1\""));
            assertTrue(body.contains("\"voice\":\"alloy\""));
            assertTrue(body.contains("\"response_format\":\"mp3\""));
            assertTrue(body.contains("\"speed\":1.3"));
        }
        assertEquals(1, meters.get("memoryos.chat.voice.request").tag("operation", "synthesize").tag("outcome", "succeeded")
                .timer().count());
    }

    @Test
    void providerFailureBeforeAudioIsReportedWithoutItsPayloadAndReleasesTheSlot() {
        status = 401;
        var failure = assertThrows(VoiceException.class,
                () -> service.stream(connection(), "", List.of("Một."), 1.0, released::incrementAndGet));
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        assertFalse(failure.getMessage().contains("diagnostic"));
        assertEquals(1, released.get());
        assertEquals("Bearer " + VoiceTranscriptionService.NO_CREDENTIAL, authorization.get());
        assertEquals(1, meters.get("memoryos.chat.voice.request").tag("operation", "synthesize").tag("outcome", "failed")
                .timer().count());
    }

    @Test
    void streamingSpeechReadsAnswerPartsThroughTheProviderInOrder() throws Exception {
        var audio = new ByteArrayOutputStream();
        try (var speech = service.streaming(connection(), "voice-secret", 1.5, audio::writeBytes, released::incrementAndGet)) {
            speech.append("Một.");
            speech.append("Hai.");
            speech.finish().get(10, TimeUnit.SECONDS);
        }
        assertEquals("mp3-1;mp3-2;", audio.toString(UTF_8));
        assertTrue(bodies.get(0).contains("\"input\":\"Một.\""));
        assertTrue(bodies.get(0).contains("\"speed\":1.5"));
        assertTrue(bodies.get(1).contains("\"input\":\"Hai.\""));
        assertEquals(1, released.get());
        assertEquals(1, meters.get("memoryos.chat.voice.request").tag("operation", "synthesize").tag("outcome", "succeeded")
                .timer().count());
    }

    @Test
    void streamingSpeechReportsProviderCallsOnlyOnceTextIsSent() throws Exception {
        var calls = new AtomicInteger();
        try (var unused = service.streaming(connection(), "voice-secret", 1.0, ignored -> {}, released::incrementAndGet,
                calls::incrementAndGet)) {
            // Closed before any answer text: the provider was never called.
        }
        assertEquals(0, calls.get());
        try (var speech = service.streaming(connection(), "voice-secret", 1.0, ignored -> {}, released::incrementAndGet,
                calls::incrementAndGet)) {
            speech.append("Một.");
            speech.finish().get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, calls.get());
    }

    @Test
    void elevenLabsSpeechStreamsThroughItsRestEndpoint() throws IOException {
        var path = new AtomicReference<String>();
        server.createContext("/v1/text-to-speech/", exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write("eleven;".getBytes(UTF_8)); }
        });
        var connection = new VoiceConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), VoiceProvider.ELEVENLABS,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", "eleven_multilingual_v2", "voice-1", null, 1);
        var output = new ByteArrayOutputStream();
        try (var speech = service.stream(connection, "eleven-secret", List.of("Xin chào."), 1.0, released::incrementAndGet)) {
            speech.writeTo(output);
        }
        assertEquals("eleven;", output.toString(UTF_8));
        assertEquals("/v1/text-to-speech/voice-1/stream", path.get());
        assertTrue(bodies.get(0).contains("\"model_id\":\"eleven_multilingual_v2\""));
        assertEquals(1, released.get());
    }

    @Test
    void closingBeforeTheAudioIsWrittenCancelsAndReleasesOnce() {
        var speech = service.stream(connection(), "voice-secret", List.of("Một.", "Hai."), 1.0, released::incrementAndGet);
        speech.close();
        speech.close();
        assertEquals(1, released.get());
        assertEquals(1, meters.get("memoryos.chat.voice.request").tag("operation", "synthesize").tag("outcome", "cancelled")
                .timer().count());
    }
}
