package io.memoryos.retrieval.settings;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import tools.jackson.databind.ObjectMapper;

/**
 * An OpenAI-compatible {@code /v1/embeddings} endpoint for tests, as TEI or vLLM serve it. It answers for the model it
 * is asked for, with the dimensions asked for (or {@link #defaultDimensions}), and records every request. A vector puts
 * weight on each word's bucket, so texts that share words are similar and search behaves like a real model's.
 */
final class FakeEmbeddingServer implements AutoCloseable {
    record Call(String model, List<String> inputs, Integer dimensions, String authorization) { }

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile int defaultDimensions = 8;
    /** A status to answer with instead of vectors, such as 401 for a wrong key; 0 answers normally. */
    volatile int failWith;

    FakeEmbeddingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/embeddings", exchange -> {
            try (exchange) {
                var request = mapper.readTree(exchange.getRequestBody());
                String model = request.path("model").asString();
                var inputs = new ArrayList<String>();
                request.path("input").forEach(value -> inputs.add(value.asString()));
                Integer dimensions = request.has("dimensions") ? request.path("dimensions").asInt() : null;
                calls.add(new Call(model, List.copyOf(inputs), dimensions,
                        String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))));
                byte[] body;
                int status = failWith;
                if (status != 0) {
                    body = "{\"error\":{\"message\":\"private provider details\"}}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 200;
                    int size = dimensions == null ? defaultDimensions : dimensions;
                    var data = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < inputs.size(); i++) data.add(Map.of("object", "embedding", "index", i, "embedding", vector(inputs.get(i), size)));
                    body = mapper.writeValueAsBytes(Map.of("object", "list", "model", model, "data", data,
                            "usage", Map.of("prompt_tokens", 4, "total_tokens", 4)));
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

    /** Requests for the model, in order. */
    List<Call> callsFor(String model) { return calls.stream().filter(call -> call.model().equals(model)).toList(); }

    static float[] vector(String text, int size) {
        float[] vector = new float[size];
        vector[0] = .1f;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) vector[Math.floorMod(word.hashCode(), size)] += 1;
        }
        return vector;
    }

    @Override public void close() { server.stop(0); }
}
