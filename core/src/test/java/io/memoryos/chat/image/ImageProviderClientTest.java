package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImageProviderClientTest {
    private final ImageHttp http = mock(ImageHttp.class);
    private final ImageConnectionService connections = mock(ImageConnectionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ImageProviderClient client = new ImageProviderClient(http, connections, meters);

    private ImageConnectionService.Connection connection(String endpoint, String model) {
        var c = new ImageConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), ImageProvider.OPENAI_IMAGE, endpoint, model, "encrypted", 1);
        when(connections.key(c)).thenReturn("test-secret");
        return c;
    }
    private static ImageHttp.Response ok(String json) { return new ImageHttp.Response(200, json.getBytes(StandardCharsets.UTF_8)); }

    @Test void decodesBase64ImageAndCapturesRevisedPrompt() throws Exception {
        byte[] png = {1, 2, 3, 4, 5};
        String b64 = Base64.getEncoder().encodeToString(png);
        when(http.post(any(), eq(Map.of("Authorization", "Bearer test-secret")), anyString()))
                .thenReturn(ok("{\"data\":[{\"b64_json\":\"" + b64 + "\",\"revised_prompt\":\"a red bicycle, cinematic\"}]}"));
        var result = client.generate(connection("https://api.openai.com/v1", "gpt-image-1"), "a red bicycle", "1024x1024");
        assertArrayEquals(png, result.bytes());
        assertEquals("image/png", result.mediaType());
        assertEquals("a red bicycle, cinematic", result.revisedPrompt());
    }

    @Test void usesConfiguredEndpointAndBearerCredential() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"AQID\"}]}"));
        client.generate(connection("http://images.internal/v1/", "gpt-image-1"), "a cat", null);
        verify(http).post(argThat(uri -> uri.toString().equals("http://images.internal/v1/images/generations")),
                eq(Map.of("Authorization", "Bearer test-secret")), anyString());
    }

    @Test void defaultsToOpenAiBaseWhenEndpointEmpty() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"AQID\"}]}"));
        var result = client.generate(connection("", ""), "a cat", null);
        assertNull(result.revisedPrompt());
        verify(http).post(argThat(uri -> uri.toString().equals("https://api.openai.com/v1/images/generations")), anyMap(), anyString());
    }

    @Test void providerFailureDoesNotDiscloseResponseSecrets() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(new ImageHttp.Response(401, "secret-provider-diagnostic".getBytes(StandardCharsets.UTF_8)));
        var error = assertThrows(IOException.class, () -> client.generate(connection("", "gpt-image-1"), "a cat", null));
        assertFalse(error.getMessage().contains("secret"));
        assertEquals(1, meters.get("memoryos.chat.image.request").tag("provider", "OPENAI_IMAGE").tag("outcome", "failed").timer().count());
    }

    @Test void missingImageDataFails() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{}]}"));
        assertThrows(IOException.class, () -> client.generate(connection("", "gpt-image-1"), "a cat", null));
    }

    @Test void invalidPromptRejectedBeforeRequest() {
        assertThrows(IllegalArgumentException.class, () -> client.generate(connection("", "gpt-image-1"), "  ", null));
    }
}
