package io.memoryos.voice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpAudioStreamTest {
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
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
        server.stop(0);
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
