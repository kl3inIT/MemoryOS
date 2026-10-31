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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ImageProviderClientTest {
    private final ImageHttp http = mock(ImageHttp.class);
    private final ImageConnectionService connections = mock(ImageConnectionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ImageProviderClient client = ImageClients.client(http, connections, meters);

    private ImageConnectionService.Connection connection(String endpoint, String model) {
        return connection(ImageProvider.OPENAI_IMAGE, endpoint, model);
    }
    private ImageConnectionService.Connection connection(ImageProvider provider, String endpoint, String model) {
        var c = new ImageConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), provider, endpoint, model, null, "encrypted", 1);
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

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    private static final String PNG_B64 = Base64.getEncoder().encodeToString(PNG);

    @Test void geminiAsksForAnAspectRatioAndKeepsTheFinalImageOverThoughts() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"thought\":true,\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AQID\"}},"
                + "{\"text\":\"Here it is\"},{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + PNG_B64 + "\"}}]}}]}"));
        var result = client.generate(connection(ImageProvider.GEMINI_IMAGE, "", "gemini-nano-banana-2.1"), "a cat", "landscape");
        assertArrayEquals(PNG, result.bytes());
        assertEquals("image/png", result.mediaType());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals(
                "https://generativelanguage.googleapis.com/v1/models/gemini-nano-banana-2.1:generateContent")),
                eq(Map.of("x-goog-api-key", "test-secret")), body.capture());
        assertTrue(body.getValue().contains("\"responseFormat\":{\"image\":{\"aspectRatio\":\"16:9\"}}"));
        assertTrue(body.getValue().contains("\"responseModalities\":[\"TEXT\",\"IMAGE\"]"));
    }

    @Test void geminiSafetyBlockWithoutAnImageFails() throws Exception {
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(ok("{\"candidates\":[{\"finishReason\":\"IMAGE_SAFETY\",\"content\":{\"parts\":[]}}]}"));
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.GEMINI_IMAGE, "", ""), "a cat", null));
    }

    @Test void geminiEditSendsTheWorkingImageInline() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"data\":\"" + PNG_B64 + "\"}}]}}]}"));
        client.edit(connection(ImageProvider.GEMINI_IMAGE, "", "gemini-3-pro-image"), "make the shirt red", working());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().endsWith("/models/gemini-3-pro-image:generateContent")), anyMap(), body.capture());
        // Map.of iterates in no fixed order, so each member is checked on its own.
        assertTrue(body.getValue().contains("\"inline_data\":{"));
        assertTrue(body.getValue().contains("\"mime_type\":\"image/png\""));
        assertTrue(body.getValue().contains("\"data\":\"Bwc=\""));
        assertTrue(body.getValue().contains("\"text\":\"make the shirt red\""));
    }

    @Test void openRouterUsesTheImagesApiAndEditsThroughAnInputReference() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        client.generate(connection(ImageProvider.OPENROUTER_IMAGE, "", "black-forest-labs/flux.2-pro"), "a cat", "portrait");
        client.edit(connection(ImageProvider.OPENROUTER_IMAGE, "", "openai/gpt-image-2"), "make it red", working());
        var bodies = ArgumentCaptor.forClass(String.class);
        verify(http, times(2)).post(argThat(uri -> uri.toString().equals("https://openrouter.ai/api/v1/images")),
                eq(Map.of("Authorization", "Bearer test-secret")), bodies.capture());
        assertTrue(bodies.getAllValues().getFirst().contains("\"aspect_ratio\":\"9:16\""));
        assertTrue(bodies.getAllValues().getLast().contains("\"input_references\":[{"));
        assertTrue(bodies.getAllValues().getLast().contains("\"image_url\":{\"url\":\"data:image/png;base64,Bwc=\"}"));
    }

    @Test void xaiAsksForBase64AndEditsWithJsonRatherThanMultipart() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"data\":[{\"b64_json\":\"" + PNG_B64 + "\"}]}"));
        client.generate(connection(ImageProvider.XAI_IMAGE, "", "grok-imagine-image-2.0"), "a cat", "square");
        client.edit(connection(ImageProvider.XAI_IMAGE, "", ""), "make it red", working());
        var generation = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals("https://api.x.ai/v1/images/generations")), anyMap(), generation.capture());
        assertTrue(generation.getValue().contains("\"response_format\":\"b64_json\""));
        assertTrue(generation.getValue().contains("\"aspect_ratio\":\"1:1\""));
        assertTrue(generation.getValue().contains("\"model\":\"grok-imagine-image-2.0\""));
        var edit = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals("https://api.x.ai/v1/images/edits")), anyMap(), edit.capture());
        assertTrue(edit.getValue().contains("\"url\":\"data:image/png;base64,Bwc=\""));
        assertTrue(edit.getValue().contains("\"type\":\"image_url\""));
    }

    @Test void falRunsSyncModeWithItsKeyAndEditsBesideTheModelEndpoint() throws Exception {
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(ok("{\"images\":[{\"url\":\"data:image/png;base64," + PNG_B64 + "\",\"content_type\":\"image/png\"}]}"));
        var result = client.generate(connection(ImageProvider.FAL_AI, "", "fal-ai/flux-2-pro"), "a cat", "landscape");
        assertArrayEquals(PNG, result.bytes());
        client.edit(connection(ImageProvider.FAL_AI, "", "fal-ai/bytedance/seedream/v5/lite/text-to-image"), "make it red", working());
        client.edit(connection(ImageProvider.FAL_AI, "", ""), "make it red", working());
        var bodies = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals("https://fal.run/fal-ai/flux-2-pro")),
                eq(Map.of("Authorization", "Key test-secret")), bodies.capture());
        assertTrue(bodies.getValue().contains("\"sync_mode\":true"));
        assertTrue(bodies.getValue().contains("\"image_size\":\"landscape_16_9\""));
        verify(http).post(argThat(uri -> uri.toString().equals("https://fal.run/fal-ai/bytedance/seedream/v5/lite/edit")),
                anyMap(), argThat(body -> body.contains("\"image_urls\":[\"data:image/png;base64,Bwc=\"]")));
        verify(http).post(argThat(uri -> uri.toString().equals("https://fal.run/fal-ai/nano-banana-2/edit")), anyMap(), anyString());
    }

    @Test void falNeverFetchesAHostedImage() throws Exception {
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(ok("{\"images\":[{\"url\":\"https://v3.fal.media/files/cat.png\"}]}"));
        assertThrows(IOException.class, () -> client.generate(connection(ImageProvider.FAL_AI, "", ""), "a cat", null));
    }

    private ImageConnectionService.Connection custom(ImageTemplate template) {
        var c = new ImageConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), ImageProvider.CUSTOM_HTTP,
                "https://api.example/v1", "flux-schnell", template, "encrypted", 1);
        when(connections.key(c)).thenReturn("test-secret");
        return c;
    }
    private static ImageTemplate.Step step(String path, String body, String pointer, ImageTemplate.Format format) {
        return new ImageTemplate.Step(path, body, pointer, format);
    }

    @Test void customSubstitutesVariablesIntoTheBodyTreeSoAPromptCannotChangeItsShape() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"out\":[{\"img\":\"" + PNG_B64 + "\"}]}"));
        var template = new ImageTemplate("X-Api-Key", "", step("/generate",
                "{\"model\":\"{{model}}\",\"input\":{\"text\":\"{{prompt}}\",\"ratio\":\"{{aspect_ratio}}\"},\"note\":\"by {{model}}\"}",
                "/out/0/img", ImageTemplate.Format.BASE64), null);
        String hostile = "a cat\", \"admin\": true, \"x\": \"";
        var result = client.generate(custom(template), hostile, "portrait");
        assertArrayEquals(PNG, result.bytes());
        var body = ArgumentCaptor.forClass(String.class);
        verify(http).post(argThat(uri -> uri.toString().equals("https://api.example/v1/generate")),
                eq(Map.of("X-Api-Key", "test-secret")), body.capture());
        var sent = new ObjectMapper().readTree(body.getValue());
        assertEquals(Set.of("model", "input", "note"), new HashSet<>(sent.propertyNames()));
        assertEquals(hostile, sent.at("/input/text").asString());
        assertEquals("9:16", sent.at("/input/ratio").asString());
        assertEquals("by flux-schnell", sent.at("/note").asString());
    }

    @Test void customDropsAWholeValueVariableWithoutAValue() throws Exception {
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"img\":\"" + PNG_B64 + "\"}"));
        var template = new ImageTemplate("Authorization", "Bearer ", step("/g",
                "{\"prompt\":\"{{prompt}}\",\"aspect_ratio\":\"{{aspect_ratio}}\"}", "/img", ImageTemplate.Format.BASE64), null);
        client.generate(custom(template), "a cat", null);
        verify(http).post(any(), eq(Map.of("Authorization", "Bearer test-secret")),
                argThat(body -> body.equals("{\"prompt\":\"a cat\"}")));
    }

    @Test void customReadsDataUrisAndRawBodiesAndEditsOnlyWhenTemplated() throws Exception {
        var dataUri = new ImageTemplate("Authorization", "Key ", step("/fal-ai/flux/schnell", "{\"prompt\":\"{{prompt}}\"}",
                "/images/0/url", ImageTemplate.Format.DATA_URI),
                step("/fal-ai/flux/edit", "{\"prompt\":\"{{prompt}}\",\"image_urls\":[\"{{image_data_uri}}\"]}",
                        "/images/0/url", ImageTemplate.Format.DATA_URI));
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(ok("{\"images\":[{\"url\":\"data:image/png;base64," + PNG_B64 + "\"}]}"));
        assertArrayEquals(PNG, client.generate(custom(dataUri), "a cat", null).bytes());
        client.edit(custom(dataUri), "make it red", working());
        verify(http).post(argThat(uri -> uri.toString().equals("https://api.example/v1/fal-ai/flux/edit")), anyMap(),
                argThat(body -> body.contains("\"image_urls\":[\"data:image/png;base64,Bwc=\"]")));

        var raw = new ImageTemplate("Authorization", "Bearer ", step("/models/flux", "{\"inputs\":\"{{prompt}}\"}", "",
                ImageTemplate.Format.RAW), null);
        when(http.post(any(), anyMap(), anyString())).thenReturn(new ImageHttp.Response(200, PNG));
        assertEquals("image/png", client.generate(custom(raw), "a cat", null).mediaType());
        assertThrows(IOException.class, () -> client.edit(custom(raw), "make it red", working()));
    }

    @Test void customNeverFetchesAHostedImageAndDisclosesNothing() throws Exception {
        var template = new ImageTemplate("Authorization", "Bearer ", step("/g", "{\"prompt\":\"{{prompt}}\"}",
                "/images/0/url", ImageTemplate.Format.DATA_URI), null);
        when(http.post(any(), anyMap(), anyString())).thenReturn(ok("{\"images\":[{\"url\":\"https://cdn.example/cat.png\"}]}"));
        assertThrows(IOException.class, () -> client.generate(custom(template), "a cat", null));
        when(http.post(any(), anyMap(), anyString()))
                .thenReturn(new ImageHttp.Response(500, "secret-diagnostic".getBytes(StandardCharsets.UTF_8)));
        var error = assertThrows(IOException.class, () -> client.generate(custom(template), "a cat", null));
        assertFalse(error.getMessage().contains("secret"));
    }
}
