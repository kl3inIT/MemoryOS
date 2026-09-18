package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImageProviderTest {
    @Test
    void everyProviderPublishesADistinctNonEmptyCatalog() {
        for (var provider : ImageProvider.values()) {
            var names = provider.knownModels().stream().map(ImageProvider.KnownModel::modelName).toList();
            assertFalse(names.isEmpty(), provider.name());
            assertEquals(names.size(), names.stream().distinct().count(), provider.name());
        }
    }

    @Test
    void cloudflareEditsUseTheFixedKleinModelWhileOpenAiEditsUseTheConfiguredModel() {
        var klein = ImageProvider.CLOUDFLARE_WORKERS_AI.editModel();
        assertNotNull(klein);
        assertEquals("@cf/black-forest-labs/flux-2-klein-9b", klein.modelName());
        assertEquals(klein.modelName(), ImageProviderClient.CLOUDFLARE_EDIT_MODEL);
        assertTrue(ImageProvider.CLOUDFLARE_WORKERS_AI.knownModels().stream().noneMatch(ImageProvider.KnownModel::edit));
        assertNull(ImageProvider.OPENAI_IMAGE.editModel());
        assertTrue(ImageProvider.OPENAI_IMAGE.knownModels().stream().allMatch(ImageProvider.KnownModel::edit));
    }

    @Test
    void onlyCloudflareRequiresAnAccountEndpoint() {
        assertTrue(ImageProvider.CLOUDFLARE_WORKERS_AI.endpointRequired());
        assertNull(ImageProvider.CLOUDFLARE_WORKERS_AI.defaultEndpoint());
        assertFalse(ImageProvider.OPENAI_IMAGE.endpointRequired());
        assertEquals("https://api.openai.com/v1", ImageProvider.OPENAI_IMAGE.defaultEndpoint());
    }

    @Test
    void cloudflareExpandsABareAccountIdIntoItsAccountEndpoint() {
        var provider = ImageProvider.CLOUDFLARE_WORKERS_AI;
        var url = "https://api.cloudflare.com/client/v4/accounts/b73a9841898f88f7cc2b731d7776f265";
        assertEquals(url, provider.normalizeEndpoint("b73a9841898f88f7cc2b731d7776f265"));
        assertEquals(url, provider.normalizeEndpoint("B73A9841898F88F7CC2B731D7776F265"));
        assertEquals(url, provider.normalizeEndpoint(url));
        assertEquals("not-an-id", provider.normalizeEndpoint("not-an-id"));
        assertEquals("https://proxy.example/v1",
                ImageProvider.OPENAI_IMAGE.normalizeEndpoint("https://proxy.example/v1"));
    }

    @Test
    void shapeResolvesToTheDeclaredSizeByAspect() {
        var provider = ImageProvider.OPENAI_IMAGE;
        assertEquals("1024x1024", provider.sizeFor("gpt-image-1", "square"));
        assertEquals("1536x1024", provider.sizeFor("gpt-image-1", "landscape"));
        assertEquals("1024x1536", provider.sizeFor("gpt-image-1", "PORTRAIT"));
        assertNull(provider.sizeFor("gpt-image-1", "wide"));
        assertNull(provider.sizeFor("gpt-image-1", null));
        assertNull(provider.sizeFor("undeclared-model", "square"));
        assertNull(ImageProvider.CLOUDFLARE_WORKERS_AI.sizeFor("@cf/black-forest-labs/flux-1-schnell", "square"));
    }

    @Test
    void rejectsInvalidModelMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new ImageProvider.KnownModel(" ", "Blank", "image/png", List.of(), false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageProvider.KnownModel("model", "Model", "image/gif", List.of(), false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageProvider.KnownModel("model", "Model", "image/png", List.of("large"), false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageProvider.KnownModel("model", " ", "image/png", List.of("1024x1024"), false, false));
    }
}
