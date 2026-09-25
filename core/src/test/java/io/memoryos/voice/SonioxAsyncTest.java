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
    private HttpServer server;
    private String status = "completed";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/files", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
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
