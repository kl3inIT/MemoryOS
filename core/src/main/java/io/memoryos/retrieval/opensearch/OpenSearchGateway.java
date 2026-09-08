package io.memoryos.retrieval.opensearch;

import io.memoryos.retrieval.SearchUnavailableException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Keeps provider responses, error bodies and SDK objects inside this adapter. */
@Component
public final class OpenSearchGateway {
    private final OpenSearchClient client;
    private final ObjectMapper mapper;

    public OpenSearchGateway(OpenSearchClient client, ObjectMapper mapper) { this.client = client; this.mapper = mapper; }

    public JsonNode json(String method, String path, Map<String,String> parameters, Object body) {
        return request(method, path, parameters, body == null ? null : mapper.writeValueAsString(body), "application/json", false);
    }

    public JsonNode bulk(String path, String body) {
        return request("POST", path, Map.of("refresh", "wait_for"), body, "application/x-ndjson", false);
    }

    public boolean exists(String path) {
        return !request("HEAD", path, Map.of(), null, "application/json", true).path("missing").asBoolean();
    }

    private JsonNode request(String method, String path, Map<String,String> parameters,
            String payload, String contentType, boolean allowMissing) {
        try (Body body = Body.from(payload == null ? null : payload.getBytes(StandardCharsets.UTF_8), contentType)) {
            var builder = Requests.builder().method(method).endpoint(path).query(parameters);
            if (body != null) builder.body(body);
            try (var response = client.generic().execute(builder.build())) {
                if (allowMissing && response.getStatus() == 404) return mapper.createObjectNode().put("missing", true);
                if (response.getStatus() < 200 || response.getStatus() >= 300) throw new SearchUnavailableException();
                var result = response.getBody().map(value -> mapper.readTree(value.bodyAsString())).orElseGet(mapper::createObjectNode);
                if (result == null) return mapper.createObjectNode();
                if (result.path("timed_out").asBoolean(false) || result.path("_shards").path("failed").asInt(0) > 0) {
                    throw new SearchUnavailableException();
                }
                return result;
            }
        } catch (IOException | UncheckedIOException | JacksonException failure) {
            // Malformed provider JSON is unavailable data; never expose its body
            // through an exception cause. Programming failures remain visible.
            throw new SearchUnavailableException();
        }
    }
}
