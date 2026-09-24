package io.memoryos.chat.catalog.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.chat.catalog.ChatProviderAdapter.ReportedModel;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalModelMetadataTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(Map<String, String> responses) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String key = path.equals("/api/show")
                    ? path + ":" + JSON.readTree(exchange.getRequestBody().readAllBytes()).path("model").asText()
                    : path;
            String body = responses.get(key);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            if (body != null) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static List<ReportedModel> names(String... ids) {
        return java.util.Arrays.stream(ids).map(ReportedModel::named).toList();
    }

    @Test
    void ollamaDetailsGiveTheContextAndCapabilitiesAndDropEmbeddingModels() throws Exception {
        // Ollama 0.12 /api/show: num_ctx in parameters wins, else <architecture>.context_length; embeddings lack completion.
        String base = serve(Map.of(
                "/api/show:qwen3:8b", """
                        {"capabilities":["completion","tools","thinking"],"parameters":"num_ctx                        16384\\nstop \\"<|im_end|>\\"",
                         "model_info":{"general.architecture":"qwen3","qwen3.context_length":40960}}""",
                "/api/show:llava:7b", """
                        {"capabilities":["completion","vision"],"model_info":{"general.architecture":"llama","llama.context_length":4096}}""",
                "/api/show:nomic-embed-text:latest", """
                        {"capabilities":["embedding"],"model_info":{"general.architecture":"nomic-bert","nomic-bert.context_length":2048}}"""));
        var data = JSON.readTree("[{\"id\":\"qwen3:8b\",\"owned_by\":\"library\"}]");
        var models = names("qwen3:8b", "llava:7b", "nomic-embed-text:latest");
        assertEquals(LocalModelMetadata.Server.OLLAMA, LocalModelMetadata.recognize(base, data, models));

        var detailed = LocalModelMetadata.enrich(HttpClient.newHttpClient(), LocalModelMetadata.Server.OLLAMA, base, "ollama",
                Duration.ofSeconds(5), models);

        assertEquals(List.of("qwen3:8b", "llava:7b"), detailed.stream().map(ReportedModel::modelName).toList());
        assertEquals(new ReportedModel("qwen3:8b", 16_384, null, true, false, true, null), detailed.get(0));
        assertEquals(new ReportedModel("llava:7b", 4096, null, false, true, false, null), detailed.get(1));
    }

    @Test
    void lmStudioReadsItsModelListAndKeepsOnlyLanguageModels() throws Exception {
        // LM Studio 0.3 /api/v1/models (Onyx): capabilities are booleans or option objects.
        String base = serve(Map.of("/api/v1/models", """
                {"models":[
                  {"type":"llm","key":"qwen/qwen3-8b","max_context_length":32768,
                   "capabilities":{"vision":false,"reasoning":{"allowed_options":["off","on"],"default":"on"},"trained_for_tool_use":true}},
                  {"type":"embedding","key":"text-embedding-nomic-embed-text-v1.5","max_context_length":2048}
                ]}"""));
        var data = JSON.readTree("[{\"id\":\"qwen/qwen3-8b\",\"owned_by\":\"organization_owner\"}]");
        var models = names("qwen/qwen3-8b", "text-embedding-nomic-embed-text-v1.5");
        assertEquals(LocalModelMetadata.Server.LM_STUDIO, LocalModelMetadata.recognize(base, data, models));

        var detailed = LocalModelMetadata.enrich(HttpClient.newHttpClient(), LocalModelMetadata.Server.LM_STUDIO, base, "lm-studio",
                Duration.ofSeconds(5), models);

        assertEquals(List.of(new ReportedModel("qwen/qwen3-8b", 32_768, null, true, false, true, null)), detailed);
    }

    @Test
    void anEndpointThatPublishesLimitsOrIsUnrecognizedIsLeftAlone() throws Exception {
        String base = serve(Map.of());
        var owned = JSON.readTree("[{\"id\":\"gpt-5-mini\",\"owned_by\":\"system\"}]");
        assertNull(LocalModelMetadata.recognize("https://api.openai.com/v1", owned, names("gpt-5-mini")));
        var published = List.of(new ReportedModel("local", 8192, null, null, null, null, null));
        assertNull(LocalModelMetadata.recognize(base, JSON.readTree("[{\"id\":\"local\",\"owned_by\":\"library\"}]"), published));
        // A native API that does not answer keeps the names already listed.
        var models = names("qwen3:8b");
        assertEquals(models, LocalModelMetadata.enrich(HttpClient.newHttpClient(), LocalModelMetadata.Server.OLLAMA, base, "k",
                Duration.ofSeconds(5), models));
    }
}
