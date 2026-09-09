package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.retrieval.SearchUnavailableException;
import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SpringAiEmbeddingHttpTest {
    @Test
    void actualSpringAiSdkUsesConfiguredModelDimensionsInputAndReportsProviderOutageSafely() throws Exception {
        var mapper = new ObjectMapper();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                var request = mapper.readTree(exchange.getRequestBody());
                assertEquals("Bearer test-only-credential", exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("text-embedding-3-large", request.path("model").asString());
                assertEquals(3072, request.path("dimensions").asInt());
                assertEquals("float", request.path("encoding_format").asString());
                assertEquals("Chính sách nghỉ phép", request.path("input").get(0).asString());
                byte[] response;
                int status;
                if (calls.incrementAndGet() == 1) {
                    float[] vector = new float[3072]; vector[0] = 1;
                    response = mapper.writeValueAsBytes(Map.of("object", "list", "model", "text-embedding-3-large",
                            "data", List.of(Map.of("object", "embedding", "index", 0, "embedding", vector)),
                            "usage", Map.of("prompt_tokens", 8, "total_tokens", 8)));
                    status = 200;
                } else {
                    response = "{\"error\":{\"message\":\"private provider details\",\"type\":\"invalid_api_key\",\"code\":\"invalid_api_key\"}}".getBytes(StandardCharsets.UTF_8);
                    status = 401;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            // This protocol fixture uses a fake credential and a local HTTP server.
            // Runtime properties reject HTTP credentials in SearchPropertiesTest.
            var properties = mock(SearchProperties.class);
            when(properties.embeddingEndpoint()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            when(properties.apiKey()).thenReturn("test-only-credential");
            when(properties.model()).thenReturn("text-embedding-3-large");
            when(properties.dimensions()).thenReturn(3072);
            when(properties.timeout()).thenReturn(Duration.ofSeconds(3));
            var model = new SearchInfrastructureConfiguration().searchEmbeddingModel(properties, ObservationRegistry.NOOP);
            var embeddings = new ValidatedEmbeddingService(model, properties.model(), 3072, 32, 2);
            assertEquals(3072, embeddings.query("Chính sách nghỉ phép").length);
            var failure = assertThrows(SearchUnavailableException.class, () -> embeddings.query("Chính sách nghỉ phép"));
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("private provider"));
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
}
