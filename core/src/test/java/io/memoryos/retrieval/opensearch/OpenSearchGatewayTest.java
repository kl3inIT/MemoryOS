package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.retrieval.SearchUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Response;
import tools.jackson.databind.ObjectMapper;

class OpenSearchGatewayTest {
    private final OpenSearchClient client = mock(OpenSearchClient.class);
    private final OpenSearchGenericClient generic = mock(OpenSearchGenericClient.class);
    private final OpenSearchGateway gateway = new OpenSearchGateway(client, new ObjectMapper());
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ch.qos.logback.core.read.ListAppender<>();
    private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenSearchGateway.class);

    @BeforeEach
    void captureLogs() { events.start(); logger.addAppender(events); }

    @AfterEach
    void releaseLogs() { logger.detachAppender(events); }

    @Test
    void logsAFailedAnswerByMethodPathTemplateStatusAndTypeWithoutItsBody() throws Exception {
        when(client.generic()).thenReturn(generic);
        var response = response(503, "{\"error\":{\"type\":\"private provider detail\"}}");
        when(generic.execute(any())).thenReturn(response);
        String chunk = "/memoryos-x/_update/0b7c1c55-7f4e-4a57-9d0c-2f3a1f2c9a10:3";
        assertThrows(SearchUnavailableException.class, () -> gateway.json("POST", chunk, Map.of(), Map.of()));
        var fields = only("search.request.failed");
        assertEquals("POST", fields.get("method"));
        assertEquals("/memoryos-x/_update/{id}:3", fields.get("path"));
        assertEquals("503", fields.get("status"));
        assertEquals("status", fields.get("error_type"));
        assertEquals(ch.qos.logback.classic.Level.WARN, events.list.getFirst().getLevel());
        assertFalse(events.list.getFirst().getFormattedMessage().contains("private"));
        assertFalse(fields.values().stream().anyMatch(value -> value.contains("private")));
        verify(response).close();
    }

    @Test
    void aMissingIndexIsAnAnswerForSearchesButAnyOther404StillFails() throws Exception {
        when(client.generic()).thenReturn(generic);
        var missingIndex = response(404, "{\"error\":{\"type\":\"index_not_found_exception\"},\"status\":404}");
        var missingPipeline = response(404, "{\"error\":{\"type\":\"resource_not_found_exception\"},\"status\":404}");
        when(generic.execute(any())).thenReturn(missingIndex, missingPipeline, missingIndex);
        assertTrue(OpenSearchGateway.indexMissing(gateway.jsonOrMissing("POST", "/memoryos-read/_search", Map.of(), Map.of())));
        assertThrows(SearchUnavailableException.class, () -> gateway.jsonOrMissing("POST", "/memoryos-read/_search", Map.of(), Map.of()));
        assertThrows(SearchUnavailableException.class, () -> gateway.json("POST", "/memoryos-read/_search", Map.of(), Map.of()),
                "Writes and admin requests that expect the index still fail when it is missing");
        assertEquals(2, events.list.size(), "An expected missing index is not a failure and is not logged");
    }

    @Test
    void recognisesAMissingAliasOnEveryBulkItem() {
        var mapper = new ObjectMapper();
        var failed = mapper.readTree("{\"errors\":true,\"items\":[{\"index\":{\"status\":404,\"error\":{\"type\":\"index_not_found_exception\"}}}]}");
        var rejected = mapper.readTree("{\"errors\":true,\"items\":[{\"update\":{\"status\":404,\"error\":{\"type\":\"document_missing_exception\"}}}]}");
        assertTrue(OpenSearchGateway.indexMissing(failed));
        assertFalse(OpenSearchGateway.indexMissing(rejected));
    }

    private Response response(int status, String body) {
        var response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        when(response.getBody()).thenReturn(Optional.of(Body.from(body.getBytes(StandardCharsets.UTF_8), "application/json")));
        return response;
    }

    private Map<String, String> only(String event) {
        var matching = events.list.stream().filter(e -> e.getKeyValuePairs() != null && e.getKeyValuePairs().stream()
                .anyMatch(pair -> "event".equals(pair.key) && event.equals(pair.value))).toList();
        assertEquals(1, matching.size());
        return matching.getFirst().getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
    }

    @Test
    void sanitizesTransportFailure() throws Exception {
        when(client.generic()).thenReturn(generic);
        when(generic.execute(any())).thenThrow(new IOException("private provider details"));
        var unavailable = assertThrows(SearchUnavailableException.class, () -> gateway.exists("/index"));
        assertNull(unavailable.getCause());
        assertFalse(unavailable.toString().contains("private provider"));
        assertEquals("java.io.IOException", only("search.request.failed").get("error_type"));
    }

    @Test
    void doesNotMaskProgrammingFailures() throws Exception {
        when(client.generic()).thenReturn(generic);
        var programmingFailure = new IllegalStateException("programming failure");
        when(generic.execute(any())).thenThrow(programmingFailure);
        assertSame(programmingFailure, assertThrows(IllegalStateException.class, () -> gateway.exists("/index")));
    }

    @Test
    void sanitizesMalformedProviderJsonAndClosesItsResponse() throws Exception {
        when(client.generic()).thenReturn(generic);
        var response = mock(Response.class);
        when(generic.execute(any())).thenReturn(response);
        when(response.getStatus()).thenReturn(200);
        when(response.getBody()).thenReturn(Optional.of(Body.from("private invalid JSON".getBytes(StandardCharsets.UTF_8), "application/json")));
        var unavailable = assertThrows(SearchUnavailableException.class,
                () -> gateway.json("GET", "/index", Map.of(), null));
        assertNull(unavailable.getCause());
        assertFalse(unavailable.toString().contains("private"));
        verify(response).close();
    }

    @Test
    void sanitizesAnInterruptedProviderBodyRead() throws Exception {
        when(client.generic()).thenReturn(generic);
        var response = mock(Response.class);
        when(generic.execute(any())).thenReturn(response);
        when(response.getStatus()).thenReturn(200);
        when(response.getBody()).thenReturn(Optional.of(Body.from(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private provider connection"); }
        }, "application/json")));
        var unavailable = assertThrows(SearchUnavailableException.class,
                () -> gateway.json("GET", "/index", Map.of(), null));
        assertNull(unavailable.getCause());
        verify(response).close();
    }
}
