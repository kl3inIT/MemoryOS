package io.memoryos.voice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpAudioStreamTest {
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/slow", exchange -> {
            requested.add(exchange.getRequestURI().getQuery());
            try {
                if (exchange.getRequestURI().getQuery().equals("headers")) {
                    // The provider takes its time to answer at all.
                    release.await(30, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(200, 0);
                } else {
                    // The provider answers, sends one chunk, and then keeps the stream open.
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("first;".getBytes(UTF_8));
                    exchange.getResponseBody().flush();
                    release.await(30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/speech", exchange -> {
            String segment = exchange.getRequestURI().getQuery();
            requested.add(segment);
            int status = segment.startsWith("reject") ? 401 : 200;
            byte[] body = (status == 200 ? "audio-" + segment + ";" : "{\"error\":\"voice-provider-diagnostic\"}").getBytes(UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void closingCancelsARequestStillWaitingForItsHeadersOnASharedClient() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var chunks = HttpAudioStream.of(client, List.of("headers"), segment -> slow(segment));
            var reading = CompletableFuture.supplyAsync(() -> chunks.iterator().hasNext());
            awaitRequested("headers");

            long started = System.nanoTime();
            chunks.close();

            assertFalse(reading.get(5, TimeUnit.SECONDS), "a stopped speech has no more audio");
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "the wait ends with the close");
            assertEquals("audio-after;", read(client, List.of("after")), "the shared client still serves the next speech");
        }
    }

    @Test
    void closingCancelsAResponseBeingRead() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var chunks = HttpAudioStream.of(client, List.of("body"), segment -> slow(segment));
            var iterator = chunks.iterator();
            assertEquals("first;", new String(iterator.next(), UTF_8));
            var reading = CompletableFuture.supplyAsync(iterator::hasNext);

            chunks.close();

            assertFalse(reading.get(5, TimeUnit.SECONDS));
        }
    }

    private String read(HttpClient client, List<String> segments) {
        var audio = new ByteArrayOutputStream();
        try (var chunks = HttpAudioStream.of(client, segments, this::request)) {
            chunks.forEach(audio::writeBytes);
        }
        return audio.toString(UTF_8);
    }

    private void awaitRequested(String segment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!requested.contains(segment) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(requested.contains(segment), "the request reached the provider");
    }

    private HttpRequest slow(String segment) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow?" + segment)).build();
    }

    private HttpRequest request(String segment) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/speech?" + segment)).build();
    }

    @Test
    void readsEachSegmentInOrderAndRequestsTheNextOnlyWhenTheFirstIsRead() {
        var audio = new ByteArrayOutputStream();
        try (var client = HttpClient.newHttpClient(); var chunks = HttpAudioStream.of(client, List.of("one", "two"), this::request)) {
            var iterator = chunks.iterator();
            audio.writeBytes(iterator.next());
            assertEquals(List.of("one"), requested);
            iterator.forEachRemaining(audio::writeBytes);
        }
        assertEquals("audio-one;audio-two;", audio.toString(UTF_8));
        assertEquals(List.of("one", "two"), requested);
    }

    @Test
    void rejectedRequestFailsWithoutReadingItsBody() {
        try (var client = HttpClient.newHttpClient(); var chunks = HttpAudioStream.of(client, List.of("ok", "reject"), this::request)) {
            var failure = assertThrows(VoiceException.class, () -> chunks.forEach(chunk -> {}));
            assertEquals("CHAT_PROVIDER_UNAVAILABLE", failure.code());
            assertFalse(failure.getMessage().contains("diagnostic"));
        }
    }
}
