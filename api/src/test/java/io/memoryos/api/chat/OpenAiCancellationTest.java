package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.chat.catalog.ChatProviderAdapter;
import io.memoryos.chat.catalog.ModelSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class OpenAiCancellationTest {
    @Test
    void cancelBeforeHeadersClosesOnlyThatNativeSubscription() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
             var client = client(adapter, server)) {
            var first = peer(server, executor, false);
            var publisher = client.binding().service().getChatModel().stream(prompt());
            var one = publisher.subscribe(ignored -> {}, ignored -> {});
            try {
                first.ready().get(10, TimeUnit.SECONDS);
                var second = peer(server, executor, false);
                var two = publisher.subscribe(ignored -> {}, ignored -> {});
                try {
                    second.ready().get(10, TimeUnit.SECONDS);
                    one.dispose();
                    assertEquals(-1, first.eof().get(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> second.eof().get(200, TimeUnit.MILLISECONDS));
                    two.dispose();
                    assertEquals(-1, second.eof().get(10, TimeUnit.SECONDS));
                } finally { two.dispose(); }
            } finally { one.dispose(); }
        } finally { meters.close(); }
    }

    @Test
    void cancelAfterContentClosesTheNativeStream() throws Exception {
        var meters = new SimpleMeterRegistry();
        try (var server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
             var client = client(adapter, server)) {
            var peer = peer(server, executor, true);
            var content = new CompletableFuture<String>();
            var stream = client.binding().service().getChatModel().stream(prompt())
                    .subscribe(response -> {
                        if (response.getResult() != null) content.complete(response.getResult().getOutput().getText());
                    }, content::completeExceptionally);
            try {
                assertEquals("Hello", content.get(10, TimeUnit.SECONDS));
                var other = peer(server, executor, false);
                var live = client.binding().service().getChatModel().stream(prompt()).subscribe(ignored -> {}, ignored -> {});
                try {
                    other.ready().get(10, TimeUnit.SECONDS);
                    stream.dispose();
                    assertEquals(-1, peer.eof().get(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> other.eof().get(200, TimeUnit.MILLISECONDS));
                    live.dispose();
                    assertEquals(-1, other.eof().get(10, TimeUnit.SECONDS));
                } finally { live.dispose(); }
            } finally { stream.dispose(); }
        } finally { meters.close(); }
    }

    private static ChatProviderAdapter.Client client(OpenAiChatProviderAdapter adapter, ServerSocket server) {
        return adapter.create(new ChatProviderAdapter.Connection("http://127.0.0.1:" + server.getLocalPort() + "/v1", "fixture-key"),
                "fixture", new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false),
                        Map.of(), null, ChatTokenizerProfiles.HOSTED), Duration.ofSeconds(30));
    }

    private static Prompt prompt() {
        return new Prompt("Question", OpenAiChatOptions.builder().model("fixture").maxTokens(16).build());
    }

    private record Peer(CompletableFuture<Void> ready, CompletableFuture<Integer> eof) {}

    private static Peer peer(ServerSocket server, ExecutorService executor, boolean sendContent) throws java.net.SocketException {
        server.setSoTimeout(15000);
        var ready = new CompletableFuture<Void>();
        var eof = new CompletableFuture<Integer>();
        executor.submit(() -> {
            try (var socket = server.accept()) {
                socket.setSoTimeout(15000);
                var input = socket.getInputStream();
                var header = new ByteArrayOutputStream();
                int state = 0;
                while (state < 4) {
                    int value = input.read();
                    if (value < 0) throw new IllegalStateException("Incomplete HTTP request");
                    header.write(value);
                    if (header.size() > 16384) throw new IllegalStateException("Oversized HTTP headers");
                    state = value == "\r\n\r\n".charAt(state) ? state + 1 : value == '\r' ? 1 : 0;
                }
                int length = header.toString(StandardCharsets.US_ASCII).lines()
                        .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                        .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim())).findFirst().orElseThrow();
                assertEquals(length, input.readNBytes(length).length);
                if (sendContent) {
                    String data = "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n";
                    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                            + "Transfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n"
                            + Integer.toHexString(bytes.length) + "\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                ready.complete(null);
                eof.complete(input.read());
            } catch (Throwable failure) {
                ready.completeExceptionally(failure);
                eof.completeExceptionally(failure);
            }
        });
        return new Peer(ready, eof);
    }
}
