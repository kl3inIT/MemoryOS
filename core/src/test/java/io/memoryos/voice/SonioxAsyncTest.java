package io.memoryos.voice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final java.util.concurrent.atomic.AtomicLong uploaded = new java.util.concurrent.atomic.AtomicLong();
    private HttpServer server;
    private String status = "completed";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/files", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            long received = 0;
            try (var body = exchange.getRequestBody()) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = body.read(buffer)) >= 0; ) received += read;
            }
            if (exchange.getRequestMethod().equals("POST")) {
                uploadLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                uploaded.set(received);
            }
            respond(exchange, 200, "{\"id\":\"file-1\"}");
        });
        server.createContext("/v1/transcriptions", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(call);
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
        try (var client = HttpClient.newHttpClient()) {
            assertEquals("Xin chào.", SonioxAsync.transcribe(client, base(), "voice-secret", "stt-rt-v5", "vi",
                    new byte[] {1, 2, 3}, TIMEOUT));
        }
        assertEquals("Bearer voice-secret", authorization.get());
        var body = JSON.readTree(created.get());
        assertEquals("stt-async-v5", body.path("model").asString());
        assertEquals("file-1", body.path("file_id").asString());
        assertEquals("vi", body.path("language_hints").get(0).asString());
        assertTrue(calls.contains("DELETE /v1/transcriptions/tx-1"));
        assertTrue(calls.contains("DELETE /v1/files/file-1"));
    }

    @Test
    void failedTranscriptionStillDeletesProviderCopiesAndHidesDetail() {
        status = "error";
        try (var client = HttpClient.newHttpClient()) {
            var failure = assertThrows(VoiceException.class, () -> SonioxAsync.transcribe(client, base(), "voice-secret",
                    "stt-rt-v5", null, new byte[] {1}, TIMEOUT));
            assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
        }
        assertTrue(calls.contains("DELETE /v1/transcriptions/tx-1"));
        assertTrue(calls.contains("DELETE /v1/files/file-1"));
        assertFalse(created.get().contains("language_hints"));
    }

    @Test
    void aRecordingIsStreamedToTheUploadInSmallReadsAndNeverReadWhole() throws Exception {
        long size = 24L * 1024 * 1024;
        var source = new GuardedAudio(size);
        var recording = new BatchTranscriptionService.Recording(source, size, "hop \"quý\".m4a", "audio/mp4");
        try (var client = HttpClient.newHttpClient()) {
            SonioxAsync.segments(client, base(), "voice-secret", "stt-rt-v5", "vi", List.of(), true, recording, TIMEOUT);
        }
        assertEquals(1, source.opened.get(), "the recording is opened once, as it is sent");
        assertEquals(size, source.served.get(), "every byte is read from the source");
        assertTrue(source.largestRead.get() <= 64 * 1024, "reads are small: " + source.largestRead.get());
        long length = Long.parseLong(uploadLength.get());
        assertEquals(length, uploaded.get(), "the multipart body declares its exact length");
        assertTrue(length > size && length < size + 1024, "the body is the recording plus its part headers");
    }

    /**
     * A recording far larger than any buffer a streamed upload needs. It refuses to be read whole, and records the
     * largest single read it was asked for, so the test fails if anything materialises the recording in memory.
     */
    private static final class GuardedAudio implements AudioSource {
        final java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong served = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger largestRead = new java.util.concurrent.atomic.AtomicInteger();
        private final long size;

        GuardedAudio(long size) {
            this.size = size;
        }

        @Override
        public java.io.InputStream open() {
            opened.incrementAndGet();
            return new java.io.InputStream() {
                private long left = size;

                @Override
                public int read() {
                    if (left == 0) return -1;
                    left--;
                    served.incrementAndGet();
                    return 7;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    largestRead.accumulateAndGet(length, Math::max);
                    if (left == 0) return -1;
                    int count = (int) Math.min(length, left);
                    java.util.Arrays.fill(buffer, offset, offset + count, (byte) 7);
                    left -= count;
                    served.addAndGet(count);
                    return count;
                }

                @Override
                public byte[] readAllBytes() {
                    throw new AssertionError("the recording was read whole");
                }

                @Override
                public byte[] readNBytes(int length) {
                    throw new AssertionError("the recording was read whole");
                }
            };
        }
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
