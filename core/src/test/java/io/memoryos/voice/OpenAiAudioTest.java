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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The recording upload of the OpenAI audio protocol, as it appears on the wire. */
class OpenAiAudioTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> start = new AtomicReference<>("");
    private final AtomicReference<String> end = new AtomicReference<>("");
    private final AtomicLong received = new AtomicLong();
    private HttpServer server;
    private int status = 200;
    private String answer = "{\"text\":\"xin chào\",\"segments\":[{\"start\":1.25,\"end\":3.5,\"text\":\" Chốt ngân sách.\"}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            var first = new StringBuilder();
            long total = 0;
            try (var body = exchange.getRequestBody()) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = body.read(buffer)) >= 0; ) {
                    // Bytes as single characters, so the parts' headers can be read beside binary audio.
                    if (first.length() < 1024) first.append(new String(buffer, 0, Math.min(read, 1024), ISO_8859_1));
                    if (read > 0) end.set(new String(buffer, Math.max(0, read - 128), Math.min(read, 128), ISO_8859_1));
                    total += read;
                }
            }
            start.set(first.toString());
            received.set(total);
            byte[] response = (status == 200 ? answer : "{\"error\":\"voice-provider-diagnostic\"}").getBytes(UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
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

    private static LiveTranscription.Options options(String language) {
        return new LiveTranscription.Options(language, List.of(), false);
    }

    @Test
    void aRecordingIsStreamedAsOneFilePartAfterItsFields() {
        long size = 24L * 1024 * 1024;
        var source = new GuardedAudio(size);
        var recording = new BatchTranscriptionService.Recording(source, size, "hop \"quý\".m4a", "audio/mp4");
        var segments = OpenAiAudio.segments(base(), "whisper-1", "voice-secret", options("vi"), recording, TIMEOUT);
        assertEquals(1, segments.size());
        assertEquals("Chốt ngân sách.", segments.getFirst().text());
        assertEquals(1250, segments.getFirst().startMs());
        assertEquals(1, source.opened.get(), "the recording is opened once, as it is sent");
        assertEquals(size, source.served.get(), "every byte is read from the source");
        assertTrue(source.largestRead.get() <= 64 * 1024, "reads are small: " + source.largestRead.get());
        assertTrue(received.get() > size && received.get() < size + 2048, "the body is the recording plus its parts");
        assertEquals("Bearer voice-secret", authorization.get());
        assertTrue(contentType.get().startsWith("multipart/form-data;"), contentType.get());
        String sent = start.get();
        assertTrue(sent.matches("(?s).*name=\"model\"\r\n(Content-[^\r]+\r\n)*\r\nwhisper-1\r\n.*"), sent);
        assertTrue(sent.matches("(?s).*name=\"response_format\"\r\n(Content-[^\r]+\r\n)*\r\nverbose_json\r\n.*"), sent);
        assertTrue(sent.matches("(?s).*name=\"language\"\r\n(Content-[^\r]+\r\n)*\r\nvi\r\n.*"), sent);
        String name = new String("hop quý.m4a".getBytes(UTF_8), ISO_8859_1);
        assertTrue(sent.contains("name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: audio/mp4"),
                "a quote in the name cannot break the part header: " + sent);
        assertTrue(sent.indexOf("name=\"language\"") < sent.indexOf("name=\"file\""), "fields come before the file");
        assertTrue(end.get().endsWith("--\r\n"), "the body ends with the closing boundary");
    }

    @Test
    void aServerWithoutAKeyStillReceivesABearerValueAndNoLanguageWhenNoneIsKnown() {
        answer = "{\"text\":\"  Cả buổi họp trong một câu.  \"}";
        var recording = new BatchTranscriptionService.Recording(AudioSource.of(new byte[] {1, 2, 3}), 3, "clip.wav", "audio/wav");
        var segments = OpenAiAudio.segments(base(), "faster-whisper", "", options(null), recording, TIMEOUT);
        assertEquals("Cả buổi họp trong một câu.", segments.getFirst().text());
        assertEquals("Bearer not-required", authorization.get());
        assertFalse(start.get().contains("name=\"language\""));
    }

    @Test
    void aRejectedUploadIsReportedWithoutThePayload() {
        status = 401;
        var recording = new BatchTranscriptionService.Recording(AudioSource.of(new byte[] {1}), 1, "clip.wav", "audio/wav");
        var failure = assertThrows(VoiceException.class,
                () -> OpenAiAudio.segments(base(), "whisper-1", "wrong", options(null), recording, TIMEOUT));
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        assertFalse(String.valueOf(failure.getMessage()).contains("diagnostic"));
    }
}
