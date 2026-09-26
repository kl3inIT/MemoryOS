package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImageProviderClientTest {
    private final ImageHttp http = mock(ImageHttp.class);
    private final ImageConnectionService connections = mock(ImageConnectionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ImageProviderClient client = new ImageProviderClient(http, connections, meters);

    private ImageConnectionService.Connection connection(String endpoint, String model) {
        return connection(ImageProvider.OPENAI_IMAGE, endpoint, model);
    }
    private ImageConnectionService.Connection connection(ImageProvider provider, String endpoint, String model) {
        var c = new ImageConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), provider, endpoint, model, "encrypted", 1);
        when(connections.key(c)).thenReturn("test-secret");
        return c;
    }
    private static ImageHttp.Response ok(String json) { return new ImageHttp.Response(200, json.getBytes(StandardCharsets.UTF_8)); }

    @Test void decodesBase64ImageAndCapturesRevisedPrompt() throws Exception {
        byte[] png = {1, 2, 3, 4, 5};
        String b64 = Base64.getEncoder().encodeToString(png);
        when(http.post(any(), eq(Map.of("Authorization", "Bearer test-secret")), anyString()))
                .thenReturn(ok("{\"data\":[{\"b64_json\":\"" + b64 + "\",\"revised_prompt\":\"a red bicycle, cinematic\"}]}"));
        var result = client.generate(connection("https://api.openai.com/v1", "gpt-image-1"), "a red bicycle", "square");
        assertArrayEquals(png, result.bytes());
        assertEquals("image/png", result.mediaType());
        assertEquals("a red bicycle, cinematic", result.revisedPrompt());
    }

    @Test void aConnectionWithoutAModelNamesTheProviderDefaultItCalls() {
        assertEquals("gpt-image-1", ImageProviderClient.resolvedModel(ImageProvider.OPENAI_IMAGE, ""));
        assertEquals("@cf/black-forest-labs/flux-1-schnell", ImageProviderClient.resolvedModel(ImageProvider.CLOUDFLARE_WORKERS_AI, " "));
        assertEquals("gpt-image-1-mini", ImageProviderClient.resolvedModel(ImageProvider.OPENAI_IMAGE, "gpt-image-1-mini"));
    }

    @Test void shapeMapsToTheCatalogSizeOfTheConfiguredModel() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"AQID\"}]}"));
        client.generate(connection("", "gpt-image-1"), "a cat", "portrait");
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(any(), anyMap(), body.capture());
        assertTrue(body.getValue().contains("\"size\":\"1024x1536\""));
    }

    @Test void shapeIsIgnoredForAModelOutsideTheCatalog() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"AQID\"}]}"));
        client.generate(connection("", "custom-model"), "a cat", "portrait");
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(any(), anyMap(), body.capture());
        assertFalse(body.getValue().contains("\"size\""));
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

    private static final String CF_BASE = "https://api.cloudflare.com/client/v4/accounts/acct123";

    @Test void cloudflareDecodesResultImageAndBuildsRunUrl() throws Exception {
        byte[] jpeg = {9, 8, 7, 6};
        String b64 = Base64.getEncoder().encodeToString(jpeg);
        when(http.post(any(), eq(Map.of("Authorization", "Bearer test-secret")), anyString()))
                .thenReturn(ok("{\"result\":{\"image\":\"" + b64 + "\"},\"success\":true}"));
        var result = client.generate(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, "@cf/black-forest-labs/flux-1-schnell"), "a red bicycle", null);
        assertArrayEquals(jpeg, result.bytes());
        assertEquals("image/jpeg", result.mediaType());
        assertNull(result.revisedPrompt());
        verify(http).post(argThat(uri -> uri.toString().equals(CF_BASE + "/ai/run/@cf/black-forest-labs/flux-1-schnell")),
                eq(Map.of("Authorization", "Bearer test-secret")), anyString());
    }

    @Test void cloudflareDefaultsModelWhenBlank() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"result\":{\"image\":\"AQID\"}}"));
        client.generate(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE + "/", ""), "a cat", null);
        verify(http).post(argThat(uri -> uri.toString().equals(CF_BASE + "/ai/run/@cf/black-forest-labs/flux-1-schnell")), anyMap(), anyString());
    }

    @Test void cloudflareRequiresAccountEndpoint() {
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, "", "@cf/black-forest-labs/flux-1-schnell"), "a cat", null));
    }

    @Test void cloudflareMissingImageFails() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"result\":{},\"success\":true}"));
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, "@cf/black-forest-labs/flux-1-schnell"), "a cat", null));
    }

    private static ImageEditImages.Working working() {
        return new ImageEditImages.Working(new byte[]{7, 7}, 384, 512,
                new BufferedImage(384, 512, BufferedImage.TYPE_INT_RGB));
    }

    @Test @SuppressWarnings("unchecked") void cloudflareEditPostsKleinMultipartAndStoresTheReturnedType() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        when(http.postMultipart(any(), anyMap(), anyMap(), anyList()))
                .thenReturn(ok("{\"result\":{\"image\":\"" + Base64.getEncoder().encodeToString(jpeg) + "\"},\"success\":true}"));
        var result = client.edit(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, "@cf/black-forest-labs/flux-1-schnell"),
                "make the shirt red", working());
        assertArrayEquals(jpeg, result.bytes());
        assertEquals("image/jpeg", result.mediaType());
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<List<ImageHttp.FilePart>> files = ArgumentCaptor.forClass(List.class);
        verify(http).postMultipart(argThat(uri -> uri.toString().equals(CF_BASE + "/ai/run/@cf/black-forest-labs/flux-2-klein-9b")),
                eq(Map.of("Authorization", "Bearer test-secret")), fields.capture(), files.capture());
        assertEquals(Map.of("prompt", "make the shirt red", "width", "384", "height", "512"), fields.getValue());
        assertEquals("input_image_0", files.getValue().getFirst().name());
        assertArrayEquals(new byte[]{7, 7}, files.getValue().getFirst().bytes());
        assertEquals(1, meters.get("memoryos.chat.image.request").tag("operation", "edit").tag("outcome", "succeeded").timer().count());
    }

    @Test @SuppressWarnings("unchecked") void openAiEditPostsImagesEditsWithHighInputFidelity() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        when(http.postMultipart(any(), anyMap(), anyMap(), anyList()))
                .thenReturn(ok("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}"));
        var result = client.edit(connection("", "gpt-image-1"), "make the shirt red", working());
        assertEquals("image/png", result.mediaType());
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<List<ImageHttp.FilePart>> files = ArgumentCaptor.forClass(List.class);
        verify(http).postMultipart(argThat(uri -> uri.toString().equals("https://api.openai.com/v1/images/edits")),
                anyMap(), fields.capture(), files.capture());
        assertEquals("gpt-image-1", fields.getValue().get("model"));
        assertEquals("high", fields.getValue().get("input_fidelity"));
        assertEquals("image", files.getValue().getFirst().name());
    }

    @Test void editFailureAndUnknownBytesDoNotDiscloseTheProviderResponse() throws Exception {
        when(http.postMultipart(any(), anyMap(), anyMap(), anyList()))
                .thenReturn(new ImageHttp.Response(400, "secret-provider-diagnostic".getBytes(StandardCharsets.UTF_8)));
        var error = assertThrows(IOException.class, () -> client.edit(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, ""), "x", working()));
        assertFalse(error.getMessage().contains("secret"));
        assertEquals(1, meters.get("memoryos.chat.image.request").tag("operation", "edit").tag("outcome", "failed").timer().count());
        when(http.postMultipart(any(), anyMap(), anyMap(), anyList())).thenReturn(ok("{\"result\":{\"image\":\"AQID\"}}"));
        assertThrows(IOException.class, () -> client.edit(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, ""), "x", working()));
    }
}
