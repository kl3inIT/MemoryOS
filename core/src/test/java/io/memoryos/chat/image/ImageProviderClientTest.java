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
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final String PNG_B64 = Base64.getEncoder().encodeToString(PNG);

    @Test void decodesBase64ImageAndCapturesRevisedPrompt() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        String b64 = Base64.getEncoder().encodeToString(png);
        when(http.post(any(), eq(Map.of("Authorization", "Bearer test-secret")), anyString()))
                .thenReturn(ok("{\"data\":[{\"b64_json\":\"" + b64 + "\",\"revised_prompt\":\"a red bicycle, cinematic\"}]}"));
        var result = client.generate(connection("https://api.openai.com/v1", "gpt-image-1"), "a red bicycle", "square");
        assertArrayEquals(png, result.bytes());
        assertEquals("image/png", result.mediaType());
        assertEquals("a red bicycle, cinematic", result.revisedPrompt());
    }

    @Test void shapeMapsToTheCatalogSizeOfTheConfiguredModel() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        client.generate(connection("", "gpt-image-1"), "a cat", "portrait");
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(any(), anyMap(), body.capture());
        assertTrue(body.getValue().contains("\"size\":\"1024x1536\""));
    }

    @Test void shapeIsIgnoredForAModelOutsideTheCatalog() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        client.generate(connection("", "custom-model"), "a cat", "portrait");
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(any(), anyMap(), body.capture());
        assertFalse(body.getValue().contains("\"size\""));
    }

    @Test void usesConfiguredEndpointAndBearerCredential() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        client.generate(connection("http://images.internal/v1/", "gpt-image-1"), "a cat", null);
        verify(http).post(argThat(uri -> uri.toString().equals("http://images.internal/v1/images/generations")),
                eq(Map.of("Authorization", "Bearer test-secret")), anyString());
    }

    @Test void defaultsToOpenAiBaseWhenEndpointEmpty() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
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
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 6};
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
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"result\":{\"image\":\"" + PNG_B64 + "\"}}"));
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
                new java.awt.image.BufferedImage(384, 512, java.awt.image.BufferedImage.TYPE_INT_RGB));
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

    private static final String AZURE_BASE = "https://contoso.openai.azure.com/openai/v1";

    @Test void azureUsesTheOpenAiProtocolWithItsApiKeyHeaderAndDeploymentName() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        var result = client.generate(connection(ImageProvider.AZURE_OPENAI_IMAGE, AZURE_BASE, "gpt-image-1"), "a cat", "landscape");
        assertEquals("image/png", result.mediaType());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals(AZURE_BASE + "/images/generations")),
                eq(Map.of("api-key", "test-secret")), body.capture());
        assertTrue(body.getValue().contains("\"model\":\"gpt-image-1\""));
        assertTrue(body.getValue().contains("\"size\":\"1536x1024\""));
        assertFalse(body.getValue().contains("response_format"));
    }

    @Test @SuppressWarnings("unchecked") void azureEditsPostImagesEditsWithItsApiKeyHeader() throws Exception {
        when(http.postMultipart(any(), anyMap(), anyMap(), anyList())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        client.edit(connection(ImageProvider.AZURE_OPENAI_IMAGE, AZURE_BASE, "images-prod"), "make it blue", working());
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(http).postMultipart(argThat(uri -> uri.toString().equals(AZURE_BASE + "/images/edits")),
                eq(Map.of("api-key", "test-secret")), fields.capture(), anyList());
        assertEquals("images-prod", fields.getValue().get("model"));
    }

    @Test void azureAndCompatibleRequireAnEndpoint() {
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.AZURE_OPENAI_IMAGE, "", "gpt-image-1"), "a cat", null));
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.OPENAI_COMPATIBLE_IMAGE, "", "flux"), "a cat", null));
    }

    @Test @SuppressWarnings("unchecked") void compatibleGatewaysAreAskedForInlineBase64AndEditWithoutInputFidelity() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(ok("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(jpeg) + "\"}]}"));
        var result = client.generate(connection(ImageProvider.OPENAI_COMPATIBLE_IMAGE, "https://api.x.ai/v1/", "grok-image"), "a cat", "square");
        assertEquals("image/jpeg", result.mediaType());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals("https://api.x.ai/v1/images/generations")),
                eq(Map.of("Authorization", "Bearer test-secret")), body.capture());
        assertTrue(body.getValue().contains("\"response_format\":\"b64_json\""));
        assertFalse(body.getValue().contains("\"size\""));

        when(http.postMultipart(any(), anyMap(), anyMap(), anyList())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        client.edit(connection(ImageProvider.OPENAI_COMPATIBLE_IMAGE, "https://gateway.internal/v1", "gpt-image-1"), "x", working());
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(http).postMultipart(any(), anyMap(), fields.capture(), anyList());
        assertFalse(fields.getValue().containsKey("input_fidelity"));
    }

    @Test void compatibleUrlOnlyResponsesAreRejectedWithoutFetching() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"url\":\"http://169.254.169.254/latest\"}]}"));
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.OPENAI_COMPATIBLE_IMAGE, "https://gw/v1", "m"), "a cat", null));
        verify(http).post(any(), anyMap(), anyString());
    }

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta";

    @Test void geminiGeneratesThroughGenerateContentWithAnAspectRatio() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"text\":\"Here is your cat\"},{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + PNG_B64 + "\"}}]}}]}"));
        var result = client.generate(connection(ImageProvider.GOOGLE_GEMINI_IMAGE, "", "gemini-2.5-flash-image"), "a cat", "portrait");
        assertArrayEquals(PNG, result.bytes());
        assertEquals("image/png", result.mediaType());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals(GEMINI_BASE + "/models/gemini-2.5-flash-image:generateContent")),
                eq(Map.of("x-goog-api-key", "test-secret")), body.capture());
        assertTrue(body.getValue().contains("\"responseModalities\":[\"TEXT\",\"IMAGE\"]"));
        assertTrue(body.getValue().contains("\"aspectRatio\":\"9:16\""));
        assertTrue(body.getValue().contains("\"text\":\"a cat\""));
    }

    @Test void geminiTextOnlyAnswersFail() throws Exception {
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(ok("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I cannot draw that\"}]}}]}"));
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.GOOGLE_GEMINI_IMAGE, "", "gemini-2.5-flash-image"), "a cat", null));
    }

    @Test void imagenGeneratesThroughPredict() throws Exception {
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(ok("{\"predictions\":[{\"mimeType\":\"image/png\",\"bytesBase64Encoded\":\"" + PNG_B64 + "\"}]}"));
        client.generate(connection(ImageProvider.GOOGLE_GEMINI_IMAGE, GEMINI_BASE + "/", "imagen-4.0-generate-001"), "a cat", "landscape");
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals(GEMINI_BASE + "/models/imagen-4.0-generate-001:predict")),
                eq(Map.of("x-goog-api-key", "test-secret")), body.capture());
        assertTrue(body.getValue().contains("\"instances\":[{\"prompt\":\"a cat\"}]"));
        assertTrue(body.getValue().contains("\"aspectRatio\":\"16:9\""));
    }

    @Test void imagenConnectionsEditWithTheGeminiFallbackAndSendTheWorkingImageInline() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + PNG_B64 + "\"}}]}}]}"));
        client.edit(connection(ImageProvider.GOOGLE_GEMINI_IMAGE, "", "imagen-4.0-generate-001"), "make it blue", working());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals(GEMINI_BASE + "/models/gemini-2.5-flash-image:generateContent")),
                anyMap(), body.capture());
        assertTrue(body.getValue().contains("\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                + Base64.getEncoder().encodeToString(new byte[]{7, 7}) + "\"}"));
    }

    @Test @SuppressWarnings("unchecked") void cloudflareFlux2GeneratesThroughMultipartAtTheShapeSize() throws Exception {
        when(http.postMultipart(any(), anyMap(), anyMap(), anyList())).thenReturn(ok("{\"result\":{\"image\":\"" + PNG_B64 + "\"}}"));
        client.generate(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, "@cf/black-forest-labs/flux-2-dev"), "a cat", "landscape");
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<List<ImageHttp.FilePart>> files = ArgumentCaptor.forClass(List.class);
        verify(http).postMultipart(argThat(uri -> uri.toString().equals(CF_BASE + "/ai/run/@cf/black-forest-labs/flux-2-dev")),
                eq(Map.of("Authorization", "Bearer test-secret")), fields.capture(), files.capture());
        assertEquals(Map.of("prompt", "a cat", "width", "1280", "height", "768"), fields.getValue());
        assertTrue(files.getValue().isEmpty());
    }

    @Test void cloudflareAcceptsBinaryImageResponsesAndSendsDeclaredSizes() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 2};
        when(http.post(any(), anyMap(), anyString())).thenReturn(new ImageHttp.Response(200, jpeg));
        var result = client.generate(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, "@cf/leonardo/phoenix-1.0"), "a cat", "portrait");
        assertArrayEquals(jpeg, result.bytes());
        assertEquals("image/jpeg", result.mediaType());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(any(), anyMap(), body.capture());
        assertTrue(body.getValue().contains("\"width\":768"));
        assertTrue(body.getValue().contains("\"height\":1280"));
        assertFalse(body.getValue().contains("\"steps\""));
    }

    @Test void cloudflareFlux2ConnectionsEditWithTheirOwnModel() throws Exception {
        when(http.postMultipart(any(), anyMap(), anyMap(), anyList())).thenReturn(ok("{\"result\":{\"image\":\"" + PNG_B64 + "\"}}"));
        client.edit(connection(ImageProvider.CLOUDFLARE_WORKERS_AI, CF_BASE, "@cf/black-forest-labs/flux-2-klein-4b"), "x", working());
        verify(http).postMultipart(argThat(uri -> uri.toString().equals(CF_BASE + "/ai/run/@cf/black-forest-labs/flux-2-klein-4b")),
                anyMap(), anyMap(), anyList());
    }
}
