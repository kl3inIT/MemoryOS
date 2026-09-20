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
    void everyVendorPublishesADistinctCatalogAndOnlyTheCompatibleProtocolIsOpen() {
        for (var provider : ImageProvider.values()) {
            var names = provider.knownModels().stream().map(ImageProvider.KnownModel::modelName).toList();
            assertEquals(provider == ImageProvider.OPENAI_COMPATIBLE_IMAGE, names.isEmpty(), provider.name());
            assertEquals(names.size(), names.stream().distinct().count(), provider.name());
            var fallback = provider.editModel();
            assertTrue(fallback == null || fallback.edit(), provider.name());
        }
    }

    @Test
    void editCapableModelsEditThemselvesAndOthersUseTheProviderFallback() {
        var cloudflare = ImageProvider.CLOUDFLARE_WORKERS_AI;
        var klein = cloudflare.editModel();
        assertNotNull(klein);
        assertEquals("@cf/black-forest-labs/flux-2-klein-9b", klein.modelName());
        assertEquals(klein.modelName(), ImageProviderClient.CLOUDFLARE_EDIT_MODEL);
        assertEquals(klein.modelName(), cloudflare.editModelFor("@cf/black-forest-labs/flux-1-schnell"));
        assertEquals(klein.modelName(), cloudflare.editModelFor("@cf/leonardo/phoenix-1.0"));
        assertEquals(klein.modelName(), cloudflare.editModelFor("@cf/unlisted/model"));
        assertEquals("@cf/black-forest-labs/flux-2-klein-4b", cloudflare.editModelFor("@cf/black-forest-labs/flux-2-klein-4b"));

        var google = ImageProvider.GOOGLE_GEMINI_IMAGE;
        assertEquals("gemini-3-pro-image-preview", google.editModelFor("gemini-3-pro-image-preview"));
        assertEquals("gemini-2.5-flash-image", google.editModelFor("imagen-4.0-generate-001"));

        assertNull(ImageProvider.OPENAI_IMAGE.editModel());
        assertTrue(ImageProvider.OPENAI_IMAGE.knownModels().stream().allMatch(ImageProvider.KnownModel::edit));
        assertEquals("custom-model", ImageProvider.OPENAI_IMAGE.editModelFor("custom-model"));
        assertEquals("my-deployment", ImageProvider.AZURE_OPENAI_IMAGE.editModelFor("my-deployment"));
        assertEquals("flux-dev", ImageProvider.OPENAI_COMPATIBLE_IMAGE.editModelFor("flux-dev"));
    }

    @Test
    void endpointDefaultsAndRequirements() {
        assertTrue(ImageProvider.CLOUDFLARE_WORKERS_AI.endpointRequired());
        assertNull(ImageProvider.CLOUDFLARE_WORKERS_AI.defaultEndpoint());
        assertTrue(ImageProvider.AZURE_OPENAI_IMAGE.endpointRequired());
        assertTrue(ImageProvider.OPENAI_COMPATIBLE_IMAGE.endpointRequired());
        assertFalse(ImageProvider.OPENAI_IMAGE.endpointRequired());
        assertEquals("https://api.openai.com/v1", ImageProvider.OPENAI_IMAGE.defaultEndpoint());
        assertFalse(ImageProvider.GOOGLE_GEMINI_IMAGE.endpointRequired());
        assertEquals("https://generativelanguage.googleapis.com/v1beta", ImageProvider.GOOGLE_GEMINI_IMAGE.defaultEndpoint());
    }

    @Test
    void azureExpandsAResourceNameOrResourceUrlIntoTheV1Base() {
        var azure = ImageProvider.AZURE_OPENAI_IMAGE;
        assertEquals("https://contoso-ai.openai.azure.com/openai/v1", azure.normalizeEndpoint("Contoso-AI"));
        assertEquals("https://contoso-ai.openai.azure.com/openai/v1", azure.normalizeEndpoint("https://contoso-ai.openai.azure.com/"));
        assertEquals("https://contoso.cognitiveservices.azure.com/openai/v1",
                azure.normalizeEndpoint("https://contoso.cognitiveservices.azure.com"));
        assertEquals("https://gateway.example/openai/v1", azure.normalizeEndpoint("https://gateway.example/openai/v1"));
        assertEquals("contoso", ImageProvider.OPENAI_COMPATIBLE_IMAGE.normalizeEndpoint("contoso"));
    }

    @Test
    void modelNamesMustBePathSafe() {
        assertTrue(ImageProvider.validModelName("@cf/black-forest-labs/flux-2-dev"));
        assertTrue(ImageProvider.validModelName("imagen-4.0-generate-001"));
        assertTrue(ImageProvider.validModelName("black-forest-labs/FLUX.1-schnell:free"));
        assertFalse(ImageProvider.validModelName("@cf/../../tokens"));
        assertFalse(ImageProvider.validModelName("model name"));
        assertFalse(ImageProvider.validModelName("model?x=1"));
        assertFalse(ImageProvider.validModelName(""));
        assertFalse(ImageProvider.validModelName(null));
    }

    @Test
    void googleShapesBecomeAspectRatios() {
        assertEquals("1:1", ImageProvider.aspectRatioFor("square"));
        assertEquals("16:9", ImageProvider.aspectRatioFor("landscape"));
        assertEquals("9:16", ImageProvider.aspectRatioFor("Portrait"));
        assertNull(ImageProvider.aspectRatioFor("wide"));
        assertNull(ImageProvider.aspectRatioFor(null));
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
