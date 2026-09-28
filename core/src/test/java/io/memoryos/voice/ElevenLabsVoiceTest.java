package io.memoryos.voice;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElevenLabsVoiceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> target = new AtomicReference<>();
    private HttpServer server;
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/speech-to-text", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("xi-api-key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), ISO_8859_1));
            byte[] response = (status == 200 ? "{\"text\":\" xin chào \"}" : "{\"detail\":\"voice-provider-diagnostic\"}").getBytes(UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.createContext("/v1/text-to-speech/", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("xi-api-key"));
            target.set(exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery());
            body.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            exchange.sendResponseHeaders(200, 3);
            try (var output = exchange.getResponseBody()) { output.write("mp3".getBytes(UTF_8)); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void transcribesAWavUploadWithTheModelLanguageAndKey() throws Exception {
        byte[] pcm = Pcm16Test.tone(0.2, 3000);
        try (var client = HttpClient.newHttpClient()) {
            assertEquals("xin chào", ElevenLabsVoice.transcribe(client, base(), "eleven-secret", "scribe_v2", "vi",
                    Pcm16.wav(pcm, 0, pcm.length), TIMEOUT));
        }
        assertEquals("eleven-secret", key.get());
        assertTrue(contentType.get().startsWith("multipart/form-data; boundary=memoryos-"));
        assertTrue(body.get().contains("name=\"model_id\"\r\n\r\nscribe_v2\r\n"));
        assertTrue(body.get().contains("name=\"language_code\"\r\n\r\nvi\r\n"));
        assertTrue(body.get().contains("name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\nRIFF"));
    }

    @Test
    void rejectedTranscriptionIsReportedWithoutThePayload() {
        status = 401;
        try (var client = HttpClient.newHttpClient()) {
            var failure = assertThrows(VoiceException.class,
                    () -> ElevenLabsVoice.transcribe(client, base(), "wrong", "scribe_v2", null, new byte[44], TIMEOUT));
            assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
            assertFalse(failure.getMessage().contains("diagnostic"));
        }
        assertFalse(body.get().contains("language_code"));
    }

    @Test
    void speechStreamsMp3ForTheVoiceWithinTheSupportedSpeed() throws Exception {
        var request = ElevenLabsVoice.speech(base(), "eleven-secret", "eleven_flash_v2_5", "voice 1", 1.5, "Xin chào", TIMEOUT);
        assertEquals("audio/mpeg", request.headers().firstValue("Accept").orElseThrow());
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(200, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals("/v1/text-to-speech/voice%201/stream?output_format=mp3_44100_128", target.get());
        assertEquals("eleven-secret", key.get());
        assertTrue(body.get().contains("\"text\":\"Xin chào\""));
        assertTrue(body.get().contains("\"model_id\":\"eleven_flash_v2_5\""));
        assertTrue(body.get().contains("\"speed\":1.2"));
    }
}
