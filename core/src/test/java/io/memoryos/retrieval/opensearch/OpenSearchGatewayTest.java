package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void sanitizesTransportFailure() throws Exception {
        when(client.generic()).thenReturn(generic);
        when(generic.execute(any())).thenThrow(new IOException("private provider details"));
        var unavailable = assertThrows(SearchUnavailableException.class, () -> gateway.exists("/index"));
        assertNull(unavailable.getCause());
        assertFalse(unavailable.toString().contains("private provider"));
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
