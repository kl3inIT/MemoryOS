package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.image.ImageProviderCapabilities.KnownModel;
import io.memoryos.chat.image.adapter.CloudflareWorkersAiImageAdapter;
import io.memoryos.chat.image.adapter.OpenAiImageAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageProviderTest {
    private final ImageAdapterRegistry registry = ImageClients.registry();

    @Test
    void everyProviderHasExactlyOneAdapterOrStartupFails() {
        assertEquals("No image adapter for CLOUDFLARE_WORKERS_AI",
                assertThrows(IllegalStateException.class, () -> new ImageAdapterRegistry(List.of(new OpenAiImageAdapter()))).getMessage());
        assertEquals("Two image adapters for OPENAI_IMAGE", assertThrows(IllegalStateException.class, () -> new ImageAdapterRegistry(
                List.of(new OpenAiImageAdapter(), new CloudflareWorkersAiImageAdapter(), new OpenAiImageAdapter()))).getMessage());
    }

    @Test
    void everyProviderPublishesADistinctNonEmptyCatalog() {
        for (var provider : ImageProvider.values()) {
            if (provider == ImageProvider.CUSTOM_HTTP) continue;
            var names = registry.capabilities(provider).knownModels().stream().map(KnownModel::modelName).toList();
            assertFalse(names.isEmpty(), provider.name());
            assertEquals(names.size(), names.stream().distinct().count(), provider.name());
            assertTrue(registry.capabilities(provider).requiresKey(), provider.name());
        }
        assertTrue(registry.capabilities(ImageProvider.CUSTOM_HTTP).knownModels().isEmpty());
        assertTrue(registry.capabilities(ImageProvider.CUSTOM_HTTP).endpointRequired());
        var duplicate = new KnownModel("m", "M", "image/png", List.of(), false, false);
        assertThrows(IllegalArgumentException.class, () -> new ImageProviderCapabilities(null, false, null, List.of(duplicate, duplicate)));
    }

    @Test
    void cloudflareEditsUseTheFixedKleinModelWhileOpenAiEditsUseTheConfiguredModel() {
        var cloudflare = registry.adapter(ImageProvider.CLOUDFLARE_WORKERS_AI);
        var klein = cloudflare.capabilities().editModel();
        assertNotNull(klein);
        assertEquals("@cf/black-forest-labs/flux-2-klein-9b", klein.modelName());
        assertEquals(klein.modelName(), cloudflare.usageModel("@cf/black-forest-labs/flux-1-schnell", true));
        assertTrue(cloudflare.capabilities().knownModels().stream().noneMatch(KnownModel::edit));
        var openAi = registry.adapter(ImageProvider.OPENAI_IMAGE);
        assertNull(openAi.capabilities().editModel());
        assertEquals("gpt-image-1.5", openAi.usageModel("gpt-image-1.5", true));
        assertTrue(openAi.capabilities().knownModels().stream().allMatch(KnownModel::edit));
    }

    @Test
    void aConnectionWithoutAModelNamesTheProviderDefaultItCalls() {
        assertEquals("gpt-image-1", registry.adapter(ImageProvider.OPENAI_IMAGE).resolvedModel(""));
        assertEquals("@cf/black-forest-labs/flux-1-schnell", registry.adapter(ImageProvider.CLOUDFLARE_WORKERS_AI).resolvedModel(" "));
        assertEquals("gpt-image-1-mini", registry.adapter(ImageProvider.OPENAI_IMAGE).resolvedModel("gpt-image-1-mini"));
    }

    @Test
    void aspectRatioProvidersEditWithTheConfiguredModel() {
        for (var provider : List.of(ImageProvider.GEMINI_IMAGE, ImageProvider.OPENROUTER_IMAGE, ImageProvider.XAI_IMAGE, ImageProvider.FAL_AI)) {
            var adapter = registry.adapter(provider);
            assertNull(adapter.capabilities().editModel(), provider.name());
            assertTrue(adapter.capabilities().knownModels().stream().allMatch(KnownModel::edit), provider.name());
            var first = adapter.capabilities().knownModels().getFirst().modelName();
            assertEquals(first, adapter.resolvedModel(""), provider.name());
            assertEquals(first, adapter.usageModel(first, true), provider.name());
        }
    }

    @Test
    void cloudflareExpandsABareAccountIdIntoItsAccountEndpoint() {
        var provider = ImageProvider.CLOUDFLARE_WORKERS_AI;
        var url = "https://api.cloudflare.com/client/v4/accounts/b73a9841898f88f7cc2b731d7776f265";
        assertEquals(url, registry.normalizeEndpoint(provider, "b73a9841898f88f7cc2b731d7776f265"));
        assertEquals(url, registry.normalizeEndpoint(provider, "B73A9841898F88F7CC2B731D7776F265"));
        assertEquals(url, registry.normalizeEndpoint(provider, url));
        assertEquals("not-an-id", registry.normalizeEndpoint(provider, "not-an-id"));
        assertEquals("https://proxy.example/v1", registry.normalizeEndpoint(ImageProvider.OPENAI_IMAGE, "https://proxy.example/v1"));
    }

    @Test
    void shapeResolvesToTheDeclaredSizeByAspect() {
        var openAi = registry.capabilities(ImageProvider.OPENAI_IMAGE);
        assertEquals("1024x1024", openAi.sizeFor("gpt-image-1", "square"));
        assertEquals("1536x1024", openAi.sizeFor("gpt-image-1", "landscape"));
        assertEquals("1024x1536", openAi.sizeFor("gpt-image-1", "PORTRAIT"));
        assertNull(openAi.sizeFor("gpt-image-1", "wide"));
        assertNull(openAi.sizeFor("gpt-image-1", null));
        assertNull(openAi.sizeFor("undeclared-model", "square"));
        assertNull(registry.capabilities(ImageProvider.CLOUDFLARE_WORKERS_AI).sizeFor("@cf/black-forest-labs/flux-1-schnell", "square"));
        var gemini = registry.capabilities(ImageProvider.GEMINI_IMAGE);
        assertEquals("1:1", gemini.sizeFor("gemini-nano-banana-2.1", "square"));
        assertEquals("16:9", gemini.sizeFor("gemini-nano-banana-2.1", "landscape"));
        assertEquals("9:16", gemini.sizeFor("gemini-nano-banana-2.1", "portrait"));
    }

    @Test
    void rejectsInvalidModelMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new KnownModel(" ", "Blank", "image/png", List.of(), false, false));
        assertThrows(IllegalArgumentException.class, () -> new KnownModel("model", "Model", "image/gif", List.of(), false, false));
        assertThrows(IllegalArgumentException.class, () -> new KnownModel("model", "Model", "image/png", List.of("large"), false, false));
        assertThrows(IllegalArgumentException.class, () -> new KnownModel("model", "Model", "image/png", List.of("16/9"), false, false));
        assertThrows(IllegalArgumentException.class, () -> new KnownModel("model", " ", "image/png", List.of("1024x1024"), false, false));
    }
}
