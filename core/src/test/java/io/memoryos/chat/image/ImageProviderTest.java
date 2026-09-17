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
