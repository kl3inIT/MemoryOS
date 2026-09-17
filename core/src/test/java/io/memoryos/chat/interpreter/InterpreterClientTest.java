package io.memoryos.chat.interpreter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterpreterClientTest {
    private HttpServer server;
    private final Map<String, String> requests = new ConcurrentHashMap<>();
    private final AtomicInteger healthCalls = new AtomicInteger();
    private volatile int healthStatus = 200;
    private final AtomicLong now = new AtomicLong();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            healthCalls.incrementAndGet();
            reply(exchange, healthStatus, "{\"status\":\"" + (healthStatus == 200 ? "ok" : "error") + "\",\"version\":\"0.1.0\"}");
        });
        server.createContext("/v1/files", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (!"k3y".equals(key)) { reply(exchange, 401, "{}"); return; }
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.put(exchange.getRequestMethod() + " " + path, body);
            switch (exchange.getRequestMethod()) {
                case "POST" -> reply(exchange, 201, "{\"file_id\":\"aaaaaaaa-0000-0000-0000-000000000001\"}");
                case "GET" -> reply(exchange, 200, "chart-bytes");
                default -> reply(exchange, 204, "");
            }
        });
        server.createContext("/v1/execute", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.put("POST /v1/execute", body);
            if (body.contains("busy")) { reply(exchange, 429, "{}"); return; }
            reply(exchange, 200, """
                    {"stdout":"hi\\n","stderr":"","exit_code":null,"timed_out":true,"duration_ms":5,
                     "files":[{"path":"chart.png","kind":"file","file_id":"bbbbbbbb-0000-0000-0000-000000000002"},
                              {"path":"out","kind":"directory","file_id":null}]}""");
        });
        server.createContext("/v1/execute/stream", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.put("POST /v1/execute/stream", body);
            if (body.contains("boom")) { reply(exchange, 200, "event: error\ndata: {\"message\":\"executor exploded\"}\n\n"); return; }
            if (body.contains("cut")) { reply(exchange, 200, "event: output\ndata: {\"stream\":\"stdout\",\"data\":\"partial\"}\n\n"); return; }
            if (body.contains("flood")) {
                // One unterminated line larger than the frame limit; readLine must refuse it, not buffer it.
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write("event: output\ndata: ".getBytes(StandardCharsets.UTF_8));
                    byte[] filler = "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
                    for (int written = 0; written < 9 * 1024 * 1024; written += filler.length) out.write(filler);
                } catch (IOException ignored) { /* the client aborts the connection, which is the point */ }
                exchange.close();
                return;
            }
            reply(exchange, 200, """
                    event: output
                    data: {"stream":"stdout","data":"step 1\\n"}

                    event: heartbeat
                    data: {}

                    event: output
                    data: {"stream":"stderr","data":"warn\\n"}

                    event: output
                    data: {"stream":"stdout","data":"step 2\\n"}

                    event: result
                    data: {"exit_code":0,"timed_out":false,"duration_ms":7,"files":[{"path":"chart.png","kind":"file","file_id":"bbbbbbbb-0000-0000-0000-000000000002"}]}

                    """);
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private InterpreterClient client() {
        return new InterpreterClient(new InterpreterProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/", "k3y"), now::get);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) try (var out = exchange.getResponseBody()) { out.write(bytes); }
        exchange.close();
    }

    @Test void healthIsCachedForThirtySecondsAndReportsServiceErrorsLikeOnyx() {
        var client = client();
        assertTrue(client.healthy());
        assertTrue(client.healthy());
        assertEquals(1, healthCalls.get());

        healthStatus = 503;
        now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(31));
        assertFalse(client.healthy());
        var health = client.health();
        assertTrue(health.connected());
        assertEquals("Code Interpreter service returned HTTP 503", health.error());
    }

    @Test void unreachableAndUnconfiguredServicesAreNotHealthy() {
        var unreachable = new InterpreterClient(new InterpreterProperties("http://127.0.0.1:1", ""), now::get).health();
        assertFalse(unreachable.connected());
        assertEquals("Unable to reach the Code Interpreter service", unreachable.error());
        var absent = new InterpreterClient(new InterpreterProperties("", ""), now::get);
        assertFalse(absent.configured());
        assertFalse(absent.healthy());
    }

    @Test void uploadsExecutesDownloadsAndDeletesWithTheApiKey() throws IOException {
        var client = client();

        String id = client.upload("dữ liệu.csv", "text/csv", new ByteArrayInputStream("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)));
        var execution = client.execute("print('hi')", 30_000, List.of(new InterpreterClient.StagedFile("dữ liệu.csv", id)));

        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", id);
        assertTrue(requests.get("POST /v1/files").contains("a,b\n1,2\n"));
        String request = requests.get("POST /v1/execute");
        assertTrue(request.contains("\"timeout_ms\":30000"));
        assertTrue(request.contains("\"file_id\":\"aaaaaaaa-0000-0000-0000-000000000001\""));
        assertEquals("hi\n", execution.stdout());
        assertNull(execution.exitCode());
        assertTrue(execution.timedOut());
        assertEquals(new InterpreterClient.WorkspaceFile("chart.png", "file", "bbbbbbbb-0000-0000-0000-000000000002"), execution.files().get(0));
        assertNull(execution.files().get(1).fileId());
        assertArrayEquals("chart-bytes".getBytes(StandardCharsets.UTF_8), client.download("bbbbbbbb-0000-0000-0000-000000000002"));
        client.delete("bbbbbbbb-0000-0000-0000-000000000002");
        assertTrue(requests.containsKey("DELETE /v1/files/bbbbbbbb-0000-0000-0000-000000000002"));
    }

    @Test void busyOversizedAndUnauthorizedResponsesAreTypedFailures() {
        var client = client();
        // Onyx hands the model the HTTP error text as it is; the body comes along, bounded.
        assertEquals("Code interpreter returned HTTP 429: {}",
                assertThrows(InterpreterClient.BusyException.class, () -> client.execute("busy", 1000, List.of())).getMessage());
        assertThrows(IOException.class, () -> client.download("../../etc"));
        var wrongKey = new InterpreterClient(new InterpreterProperties("http://127.0.0.1:" + server.getAddress().getPort(), "wrong"), now::get);
        assertThrows(IOException.class, () -> wrongKey.delete("cccccccc-0000-0000-0000-000000000003"));
    }

    @Test void streamingReportsOutputAsItArrivesAndReturnsTheFinalResult() throws Exception {
        var seen = new ArrayList<String>();

        var execution = client().executeStream("print(1)", 1000, List.of(),
                (stream, data) -> seen.add(stream + ":" + data));

        assertEquals(List.of("stdout:step 1\n", "stderr:warn\n", "stdout:step 2\n"), seen);
        assertEquals("step 1\nstep 2\n", execution.stdout());
        assertEquals("warn\n", execution.stderr());
        assertEquals(0, execution.exitCode());
        assertFalse(execution.timedOut());
        assertEquals("bbbbbbbb-0000-0000-0000-000000000002", execution.files().getFirst().fileId());
    }

    @Test void aStreamErrorEndedEarlyOrAbandonedByTheListenerFails() {
        var client = client();

        // The service's own error event, a stream that stops before its result, and a caller that stops reading
        // (a Stop, which must abandon the body so the container is killed) are all failures, never a partial result.
        assertEquals("Code interpreter error: executor exploded", assertThrows(IOException.class,
                () -> client.executeStream("boom", 1000, List.of(), (stream, data) -> { })).getMessage());
        assertEquals("Code interpreter stream ended without a result event", assertThrows(IOException.class,
                () -> client.executeStream("cut", 1000, List.of(), (stream, data) -> { })).getMessage());
        assertThrows(IOException.class, () -> client.executeStream("print(1)", 1000, List.of(),
                (stream, data) -> { throw new IOException("stopped"); }));
    }

    @Test void anUnterminatedFrameIsRefusedInsteadOfBuffered() {
        assertThrows(IOException.class, () -> client().executeStream("flood", 1000, List.of(), (stream, data) -> { }));
    }

    @Test void propertiesRejectCredentialsInTheUrlAndRedactTheKey() {
        assertThrows(IllegalArgumentException.class, () -> new InterpreterProperties("http://user:pass@host:8000", ""));
        assertThrows(IllegalArgumentException.class, () -> new InterpreterProperties("ftp://host", ""));
        assertFalse(new InterpreterProperties("http://memoryos-interpreter:8000", "secret").toString().contains("secret"));
    }
}
