package io.memoryos.chat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebProviderClientTest {
    private final WebHttp http = mock(WebHttp.class);
    private final WebConnectionService connections = mock(WebConnectionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WebProviderClient client = new WebProviderClient(http, connections, meters);
    private WebConnectionService.Connection connection(WebProvider provider) {
        var c = new WebConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), provider, "http://search.internal", "engine", "encrypted", 1);
        when(connections.key(c)).thenReturn("test-secret"); return c;
    }
    @Test void braveNormalizesEvidenceWithoutUsingCredentialsInPageRequests() throws Exception {
        when(http.provider(eq("GET"), any(), eq(Map.of("X-Subscription-Token", "test-secret")), isNull())).thenReturn(response("{\"web\":{\"results\":[{\"title\":\"News\",\"url\":\"https://example.com/news\",\"description\":\"Summary\"},{\"url\":\"http://127.0.0.1\"}]}}"));
        var result = client.search(connection(WebProvider.BRAVE), "news & updates");
        assertEquals(1, result.size()); assertEquals("Summary", result.getFirst().text());
        verify(http).provider(eq("GET"), argThat(uri -> uri.getRawQuery().contains("news+%26+updates")), anyMap(), isNull());
    }
    @Test void tavilyUsesSameConnectionForSearchAndExtract() throws Exception {
        var c = connection(WebProvider.TAVILY);
        when(http.provider(eq("POST"), any(), eq(Map.of("Authorization", "Bearer test-secret")), anyString())).thenReturn(
                response("{\"results\":[{\"url\":\"https://example.com\",\"title\":\"Title\",\"content\":\"Snippet\"}]}"),
                response("{\"results\":[{\"url\":\"https://example.com\",\"raw_content\":\"Full content\"}]}"));
        assertEquals("Snippet", client.search(c, "example").getFirst().text());
        assertEquals("Full content", client.read(c, "https://example.com", () -> {}).text());
        verify(connections, times(2)).key(c);
    }
    @Test void builtInReaderStripsExecutableContentAndRejectsUnsupportedTypes() throws Exception {
        when(http.page(anyString(), any())).thenReturn(new WebHttp.Response(200, "text/html", "<title>Article</title><main>Real text</main><script>steal()</script><nav>Navigation</nav>".getBytes(StandardCharsets.UTF_8), null));
        var read = client.read(null, "https://example.com", () -> {});
        assertEquals("Article", read.title()); assertEquals("Real text", read.text());
        verifyNoInteractions(connections);
        when(http.page(anyString(), any())).thenReturn(new WebHttp.Response(200, "application/octet-stream", new byte[]{1}, null));
        assertThrows(IOException.class, () -> client.read(null, "https://example.com", () -> {}));
    }
    @Test void providerFailureDoesNotDiscloseResponseSecrets() throws Exception {
        when(http.provider(anyString(), any(), anyMap(), any())).thenReturn(new WebHttp.Response(401, "application/json", "secret-provider-diagnostic".getBytes(StandardCharsets.UTF_8), null));
        var error = assertThrows(IOException.class, () -> client.search(connection(WebProvider.BRAVE), "example"));
        assertFalse(error.getMessage().contains("secret"));
        assertEquals(1, meters.get("memoryos.chat.web.request").tag("provider", "BRAVE").tag("outcome", "failed").timer().count());
    }
    @Test void remainingSearchProvidersMapTheirActualResponseFields() throws Exception {
        var fixtures = Map.of(
                WebProvider.EXA, "{\"results\":[{\"url\":\"https://example.com\",\"title\":\"Title\",\"text\":\"Exa text\"}]}",
                WebProvider.SERPER, "{\"organic\":[{\"link\":\"https://example.com\",\"title\":\"Title\",\"snippet\":\"Serper text\"}]}",
                WebProvider.GOOGLE_PSE, "{\"items\":[{\"link\":\"https://example.com\",\"title\":\"Title\",\"snippet\":\"Google text\"}]}",
                WebProvider.SEARXNG, "{\"results\":[{\"url\":\"https://example.com\",\"title\":\"Title\",\"content\":\"SearXNG text\"}]}");
        for (var fixture : fixtures.entrySet()) {
            when(http.provider(anyString(), any(), anyMap(), any())).thenReturn(response(fixture.getValue()));
            var result = client.search(connection(fixture.getKey()), "example");
            assertEquals(1, result.size(), fixture.getKey().name());
            assertEquals("https://example.com", result.getFirst().url());
            assertTrue(result.getFirst().text().endsWith("text"), fixture.getKey().name());
        }
    }
    @Test void configuredEndpointReplacesTheProviderDefaultBase() throws Exception {
        when(http.provider(anyString(), any(), anyMap(), any())).thenReturn(response("{\"results\":[{\"url\":\"https://example.com\",\"title\":\"T\",\"text\":\"x\"}]}"));
        client.search(connection(WebProvider.EXA), "example");
        verify(http).provider(eq("POST"), argThat(uri -> uri.toString().startsWith("http://search.internal/search")), anyMap(), anyString());
    }
    @Test void exaAndFirecrawlReturnExtractedTextAndRejectFailedExtraction() throws Exception {
        when(http.provider(anyString(), any(), anyMap(), any())).thenReturn(response("{\"results\":[{\"text\":\"Exa page\"}]}"));
        assertEquals("Exa page", client.read(connection(WebProvider.EXA), "https://example.com", () -> {}).text());
        when(http.provider(anyString(), any(), anyMap(), any())).thenReturn(response("{\"success\":true,\"data\":{\"markdown\":\"Article text\",\"metadata\":{\"title\":\"Article\"}}}"));
        var firecrawl = connection(WebProvider.FIRECRAWL);
        assertEquals("Article text", client.read(firecrawl, "https://example.com", () -> {}).text());
        when(http.provider(anyString(), any(), anyMap(), any())).thenReturn(response("{\"success\":false}"));
        assertThrows(IOException.class, () -> client.read(firecrawl, "https://example.com", () -> {}));
    }
    private static WebHttp.Response response(String json) { return new WebHttp.Response(200, "application/json", json.getBytes(StandardCharsets.UTF_8), null); }
}
