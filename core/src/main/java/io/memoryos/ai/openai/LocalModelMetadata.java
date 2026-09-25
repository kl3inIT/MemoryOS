package io.memoryos.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memoryos.ai.ProviderAdapter.ReportedModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ollama and LM Studio answer {@code /v1/models} with names only. As Onyx's per-provider fetchers
 * ({@code /ollama/available-models}, {@code /lm-studio/available-models}), their native APIs are read for the context
 * window and capabilities: Ollama {@code /api/show} per model, LM Studio {@code /api/v1/models} (or {@code /api/v0}).
 * The server is recognized from the {@code owned_by} its OpenAI route reports or its default port. Any failure keeps
 * the names already listed, so discovery never fails on the extra reads.
 */
final class LocalModelMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(LocalModelMetadata.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Ollama needs one {@code /api/show} per model; a larger server keeps its names. */
    static final int MAX_OLLAMA_MODELS = 60;
    private static final int MAX_BODY_BYTES = 4 * 1_048_576;

    enum Server { OLLAMA, LM_STUDIO }

    private LocalModelMetadata() {}

    /** The local server behind an OpenAI route whose models publish nothing, or null for any other endpoint. */
    static @Nullable Server recognize(String baseUrl, JsonNode data, List<ReportedModel> models) {
        if (models.isEmpty() || models.stream().anyMatch(model -> model.contextWindow() != null)) return null;
        String owner = data.isArray() && !data.isEmpty() ? data.get(0).path("owned_by").asText("") : "";
        int port = URI.create(baseUrl).getPort();
        if ("library".equals(owner) || port == 11434) return Server.OLLAMA;
        if ("organization_owner".equals(owner) || port == 1234) return Server.LM_STUDIO;
        return null;
    }

    static List<ReportedModel> enrich(HttpClient client, Server server, String baseUrl, String credential, Duration timeout,
                                      List<ReportedModel> models) {
        String root = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
        try {
            return server == Server.OLLAMA ? ollama(client, root, credential, timeout, models)
                    : lmStudio(client, root, credential, timeout, models);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return models;
        } catch (Exception failure) {
            LOG.warn("{} model details could not be read ({})", server, failure.getClass().getSimpleName());
            return models;
        }
    }

    /** Onyx {@code OllamaModelDetails}: {@code num_ctx}, else {@code <architecture>.context_length}; completion models only. */
    private static List<ReportedModel> ollama(HttpClient client, String root, String credential, Duration timeout,
                                              List<ReportedModel> models) throws Exception {
        if (models.size() > MAX_OLLAMA_MODELS) return models;
        var detailed = new ArrayList<ReportedModel>();
        for (var model : models) {
            var show = read(client, HttpRequest.newBuilder(URI.create(root + "/api/show"))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("model", model.modelName())))),
                    credential, timeout);
            if (show == null) {
                detailed.add(model);
                continue;
            }
            var capabilities = new ArrayList<String>();
            show.path("capabilities").forEach(value -> capabilities.add(value.asText()));
            // An embedding model has no "completion" capability and cannot answer a Chat turn.
            if (!capabilities.isEmpty() && !capabilities.contains("completion")) continue;
            Integer context = numCtx(show.path("parameters").asText(""));
            if (context == null) {
                String architecture = show.path("model_info").path("general.architecture").asText("");
                var declared = show.path("model_info").path(architecture + ".context_length");
                context = declared.canConvertToInt() && declared.asInt() > 0 ? declared.asInt() : null;
            }
            boolean known = !capabilities.isEmpty();
            detailed.add(new ReportedModel(model.modelName(), context, null,
                    known ? capabilities.contains("tools") : null, known ? capabilities.contains("vision") : null,
                    known ? capabilities.contains("thinking") : null, null));
        }
        return detailed;
    }

    private static @Nullable Integer numCtx(String parameters) {
        for (String line : parameters.split("\\R")) {
            String[] tokens = line.trim().split("\\s+", 2);
            if (tokens.length == 2 && tokens[0].equals("num_ctx")) {
                try {
                    int value = Integer.parseInt(tokens[1].trim());
                    return value > 0 ? value : null;
                } catch (NumberFormatException invalid) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * LM Studio {@code /api/v1/models} lists {@code models[]} with {@code type}, {@code key}, {@code max_context_length}
     * and a {@code capabilities} object (Onyx); the older {@code /api/v0/models} lists {@code data[]} with {@code id} and
     * a {@code capabilities} array ({@code tool_use}). Only {@code llm} models are kept.
     */
    private static List<ReportedModel> lmStudio(HttpClient client, String root, String credential, Duration timeout,
                                                List<ReportedModel> models) throws Exception {
        var byName = new HashMap<String, JsonNode>();
        var v1 = read(client, HttpRequest.newBuilder(URI.create(root + "/api/v1/models")).GET(), credential, timeout);
        if (v1 != null) v1.path("models").forEach(item -> byName.put(item.path("key").asText(""), item));
        else {
            var v0 = read(client, HttpRequest.newBuilder(URI.create(root + "/api/v0/models")).GET(), credential, timeout);
            if (v0 == null) return models;
            v0.path("data").forEach(item -> byName.put(item.path("id").asText(""), item));
        }
        if (byName.isEmpty()) return models;
        var detailed = new ArrayList<ReportedModel>();
        for (var model : models) {
            var item = byName.get(model.modelName());
            if (item == null) {
                detailed.add(model);
                continue;
            }
            if (!"llm".equals(item.path("type").asText("llm")) && !"vlm".equals(item.path("type").asText(""))) continue;
            var declared = item.path("max_context_length");
            Integer context = declared.canConvertToInt() && declared.asInt() > 0 ? declared.asInt() : null;
            var capabilities = item.path("capabilities");
            Boolean tools = null, vision = null, reasoning = null;
            if (capabilities.isArray()) {
                var names = new ArrayList<String>();
                capabilities.forEach(value -> names.add(value.asText()));
                tools = names.contains("tool_use");
            } else if (capabilities.isObject()) {
                vision = enabled(capabilities.path("vision"));
                reasoning = enabled(capabilities.path("reasoning"));
                if (capabilities.has("trained_for_tool_use")) tools = enabled(capabilities.path("trained_for_tool_use"));
            }
            if ("vlm".equals(item.path("type").asText(""))) vision = true;
            detailed.add(new ReportedModel(model.modelName(), context, null, tools, vision, reasoning, null));
        }
        return detailed;
    }

    /** Onyx {@code lm_studio_capability_enabled}: a boolean, or an options object unless "off" is the only option. */
    private static @Nullable Boolean enabled(JsonNode value) {
        if (value.isBoolean()) return value.asBoolean();
        if (value.isObject() && value.path("allowed_options").isArray()) {
            for (var option : value.path("allowed_options")) if (!"off".equalsIgnoreCase(option.asText())) return true;
            return false;
        }
        return null;
    }

    private static @Nullable JsonNode read(HttpClient client, HttpRequest.Builder request, String credential, Duration timeout)
            throws Exception {
        var built = request.timeout(timeout).header("Accept", "application/json").header("Content-Type", "application/json");
        if (!credential.isBlank()) built.header("Authorization", "Bearer " + credential);
        var response = client.send(built.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (var stream = response.body()) { body = stream.readNBytes(MAX_BODY_BYTES + 1); }
        if (response.statusCode() != 200 || body.length == 0 || body.length > MAX_BODY_BYTES) return null;
        return JSON.readTree(body);
    }
}
