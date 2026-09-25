package io.memoryos.retrieval.opensearch;

import io.memoryos.retrieval.SearchUnavailableException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps provider responses, error bodies and SDK objects inside this adapter. A failure is logged here once, with the
 * method, the path template, the status and the failure's type, and leaves as a {@link SearchUnavailableException}
 * without a cause: provider messages and bodies (a JSON parse error quotes the body) never reach callers or logs.
 */
@Component
public final class OpenSearchGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSearchGateway.class);
    private static final Pattern UUID_SEGMENT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final String INDEX_NOT_FOUND = "index_not_found_exception";
    private final OpenSearchClient client;
    private final ObjectMapper mapper;

    /** Which 404 answers mean "absent" rather than a failure. */
    private enum Missing { NEVER, ANY, INDEX }

    public OpenSearchGateway(OpenSearchClient client, ObjectMapper mapper) { this.client = client; this.mapper = mapper; }

    public JsonNode json(String method, String path, Map<String,String> parameters, Object body) {
        return request(method, path, parameters, body == null ? null : mapper.writeValueAsString(body), "application/json", Missing.NEVER);
    }

    /**
     * As {@link #json}, but an index or alias that does not exist answers {@code {"missing": true}} instead of failing,
     * so callers need no existence check before each request. Other 404 answers still fail.
     */
    public JsonNode jsonOrMissing(String method, String path, Map<String,String> parameters, Object body) {
        return request(method, path, parameters, body == null ? null : mapper.writeValueAsString(body), "application/json", Missing.INDEX);
    }

    public JsonNode bulk(String path, String body) {
        return request("POST", path, Map.of("refresh", "wait_for"), body, "application/x-ndjson", Missing.NEVER);
    }

    /**
     * Bulk writes through an alias with {@code require_alias}: when the alias is gone with its index, every item fails
     * with {@link #indexMissing} instead of OpenSearch creating an unmapped index under the alias name.
     */
    public JsonNode bulkThroughAlias(String alias, String body) {
        return request("POST", "/" + alias + "/_bulk", Map.of("refresh", "wait_for", "require_alias", "true"), body,
                "application/x-ndjson", Missing.INDEX);
    }

    /** The answer said the index or alias does not exist, for the request or for any item of a bulk request. */
    public static boolean indexMissing(JsonNode response) {
        if (response.path("missing").asBoolean(false)) return true;
        for (JsonNode item : response.path("items")) {
            for (JsonNode action : item) {
                if (INDEX_NOT_FOUND.equals(action.path("error").path("type").asString(""))) return true;
            }
        }
        return false;
    }

    /** Deletes the resource; returns false when it did not exist. */
    public boolean delete(String path) {
        return !request("DELETE", path, Map.of(), null, "application/json", Missing.ANY).path("missing").asBoolean();
    }

    public boolean exists(String path) {
        return !request("HEAD", path, Map.of(), null, "application/json", Missing.ANY).path("missing").asBoolean();
    }

    private JsonNode request(String method, String path, Map<String,String> parameters,
            String payload, String contentType, Missing missing) {
        int status = 0;
        try (Body body = Body.from(payload == null ? null : payload.getBytes(StandardCharsets.UTF_8), contentType)) {
            var builder = Requests.builder().method(method).endpoint(path).query(parameters);
            if (body != null) builder.body(body);
            try (var response = client.generic().execute(builder.build())) {
                status = response.getStatus();
                if (status == 404 && missing == Missing.ANY) return absent();
                if (status < 200 || status >= 300) {
                    if (status == 404 && missing == Missing.INDEX && INDEX_NOT_FOUND.equals(errorType(response.getBody().orElse(null)))) {
                        return absent();
                    }
                    throw unavailable(method, path, status, "status");
                }
                var result = response.getBody().map(value -> mapper.readTree(value.bodyAsString())).orElseGet(mapper::createObjectNode);
                if (result == null) return mapper.createObjectNode();
                if (result.path("timed_out").asBoolean(false)) throw unavailable(method, path, status, "timed_out");
                if (result.path("_shards").path("failed").asInt(0) > 0) throw unavailable(method, path, status, "shard_failure");
                return result;
            }
        } catch (IOException | UncheckedIOException | JacksonException failure) {
            // Malformed provider JSON is unavailable data; never expose its body
            // through an exception cause. Programming failures remain visible.
            throw unavailable(method, path, status, failure.getClass().getName());
        }
    }

    private JsonNode absent() { return mapper.createObjectNode().put("missing", true); }

    /** The error type of a failed answer; the body itself is neither kept nor logged. */
    private String errorType(Body body) {
        if (body == null) return "";
        try {
            var parsed = mapper.readTree(body.bodyAsString());
            return parsed == null ? "" : parsed.path("error").path("type").asString("");
        } catch (JacksonException | UncheckedIOException failure) {
            return "";
        }
    }

    private static SearchUnavailableException unavailable(String method, String path, int status, String errorType) {
        LOGGER.atWarn().addKeyValue("event", "search.request.failed").addKeyValue("method", method)
                .addKeyValue("path", template(path)).addKeyValue("status", status).addKeyValue("error_type", errorType)
                .log("The search engine request failed");
        return new SearchUnavailableException();
    }

    /** The request path with document, chunk and generation identifiers replaced, so it stays a bounded template. */
    static String template(String path) {
        return UUID_SEGMENT.matcher(path).replaceAll("{id}");
    }
}
