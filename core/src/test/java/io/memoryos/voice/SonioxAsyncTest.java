package io.memoryos.voice;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SonioxAsyncTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> created = new AtomicReference<>();
    private final AtomicReference<String> uploadLength = new AtomicReference<>();
    private final AtomicReference<String> uploadType = new AtomicReference<>();
    private final AtomicReference<String> uploadStart = new AtomicReference<>("");
    private final AtomicLong uploaded = new AtomicLong();
    private HttpServer server;
    private String status = "completed";
    private int uploadStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/files", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            long received = 0;
            var start = new StringBuilder();
            try (var body = exchange.getRequestBody()) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = body.read(buffer)) >= 0; ) {
                    // Bytes as single characters, so the part's headers can be read beside binary audio.
                    if (start.length() < 512) start.append(new String(buffer, 0, Math.min(read, 512), ISO_8859_1));
                    received += read;
                }
            }
            if (exchange.getRequestMethod().equals("POST")) {
                uploadLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                uploadType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                uploadStart.set(start.toString());
                uploaded.set(received);
            }
            respond(exchange, uploadStatus, uploadStatus == 200 ? "{\"id\":\"file-1\"}" : "{\"message\":\"voice-provider-diagnostic\"}");
        });
        server.createContext("/v1/transcriptions", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(call);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (call.equals("POST /v1/transcriptions")) {
                created.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                respond(exchange, 200, "{\"id\":\"tx-1\",\"status\":\"queued\"}");
            } else if (call.endsWith("/transcript")) {
                respond(exchange, 200, "{\"text\":\" Xin chào. \",\"tokens\":[]}");
            } else if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "{\"id\":\"tx-1\",\"status\":\"" + status + "\"}");
            } else {
                respond(exchange, 204, "");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void transcribesWithTheAsyncModelAndDeletesTheTranscriptionAndFile() throws Exception {
        assertEquals("Xin chào.", SonioxAsync.transcribe(base(), "voice-secret", "stt-rt-v5", "vi", new byte[] {1, 2, 3},
                TIMEOUT));
        assertEquals("Bearer voice-secret", authorization.get());
        var body = JSON.readTree(created.get());
        assertEquals("stt-async-v5", body.path("model").asString());
        assertEquals("file-1", body.path("file_id").asString());
        assertEquals("vi", body.path("language_hints").get(0).asString());
        assertEquals(List.of("POST /v1/files", "POST /v1/transcriptions", "GET /v1/transcriptions/tx-1",
                "GET /v1/transcriptions/tx-1/transcript", "DELETE /v1/transcriptions/tx-1", "DELETE /v1/files/file-1"), calls);
        assertTrue(uploadStart.get().contains("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\""),
                uploadStart.get());
        assertTrue(uploadStart.get().contains("Content-Type: audio/wav"), uploadStart.get());
    }

    @Test
    void failedTranscriptionStillDeletesProviderCopiesAndHidesDetail() {
        status = "error";
        var failure = assertThrows(VoiceException.class, () -> SonioxAsync.transcribe(base(), "voice-secret", "stt-rt-v5",
                null, new byte[] {1}, TIMEOUT));
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        assertTrue(calls.contains("DELETE /v1/transcriptions/tx-1"));
        assertTrue(calls.contains("DELETE /v1/files/file-1"));
        assertFalse(created.get().contains("language_hints"));
    }

    @Test
    void aRejectedUploadIsReportedWithoutThePayloadAndLeavesNothingToDelete() {
        uploadStatus = 401;
        var failure = assertThrows(VoiceException.class, () -> SonioxAsync.transcribe(base(), "wrong", "stt-rt-v5", null,
                new byte[] {1}, TIMEOUT));
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        assertFalse(String.valueOf(failure.getMessage()).contains("diagnostic"));
        assertEquals(List.of("POST /v1/files"), calls);
    }

    @Test
    void aRecordingIsStreamedToTheUploadInSmallReadsAndNeverReadWhole() throws Exception {
        long size = 24L * 1024 * 1024;
        var source = new GuardedAudio(size);
        var recording = new BatchTranscriptionService.Recording(source, size, "hop \"quý\".m4a", "audio/mp4");
        SonioxAsync.segments(base(), "voice-secret", "stt-rt-v5", "vi", List.of(), true, recording, TIMEOUT);
        assertEquals(1, source.opened.get(), "the recording is opened once, as it is sent");
        assertEquals(size, source.served.get(), "every byte is read from the source");
        assertTrue(source.largestRead.get() <= 64 * 1024, "reads are small: " + source.largestRead.get());
        // Spring writes the multipart body as it goes, so the request is chunked and declares no length.
        assertNull(uploadLength.get());
        assertTrue(uploaded.get() > size && uploaded.get() < size + 1024, "the body is the recording plus its part headers");
        assertTrue(uploadType.get().startsWith("multipart/form-data;"), uploadType.get());
        String name = new String("hop quý.m4a".getBytes(UTF_8), ISO_8859_1);
        assertTrue(uploadStart.get().contains("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\""),
                "a quote in the name cannot break the part header: " + uploadStart.get());
        assertTrue(uploadStart.get().contains("Content-Type: audio/mp4"), uploadStart.get());
        assertTrue(created.get().contains("\"enable_speaker_diarization\":true"));
    }

    @Test
    void realtimeModelsMapToTheirAsyncSibling() {
        assertEquals("stt-async-v5", SonioxAsync.asyncModel("stt-rt-v5"));
        assertEquals("stt-async-v6", SonioxAsync.asyncModel("stt-async-v6"));
        assertEquals(SonioxAsync.DEFAULT_MODEL, SonioxAsync.asyncModel("custom"));
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
