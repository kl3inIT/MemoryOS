package io.memoryos.chat.image;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * Exactly one adapter per {@link ImageProvider}, checked when the application starts: a provider without an adapter,
 * or with two, fails startup rather than a request.
 */
@Component
@NamedInterface("image")
public final class ImageAdapterRegistry {
    private final Map<ImageProvider, ImageGenerationAdapter> adapters = new EnumMap<>(ImageProvider.class);

    public ImageAdapterRegistry(List<ImageGenerationAdapter> adapters) {
        for (var adapter : adapters)
            if (this.adapters.putIfAbsent(adapter.provider(), adapter) != null)
                throw new IllegalStateException("Two image adapters for " + adapter.provider());
        for (var provider : ImageProvider.values())
            if (!this.adapters.containsKey(provider)) throw new IllegalStateException("No image adapter for " + provider);
    }

    public ImageProviderCapabilities capabilities(ImageProvider provider) { return adapter(provider).capabilities(); }

    /** The endpoint to store for what a manager typed for this provider. */
    public String normalizeEndpoint(ImageProvider provider, String endpoint) {
        return adapter(provider).normalizeEndpoint(endpoint);
    }

    ImageGenerationAdapter adapter(ImageProvider provider) { return adapters.get(provider); }
}
