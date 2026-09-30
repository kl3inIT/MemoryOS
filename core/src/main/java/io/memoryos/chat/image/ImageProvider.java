package io.memoryos.chat.image;

import org.springframework.modulith.NamedInterface;

/**
 * The implemented image protocols, persisted and in the API. Identity only: what each needs and the models it serves
 * come from its adapter through {@link ImageAdapterRegistry}. Native model tools are not image-provider connections.
 */
@NamedInterface("image")
public enum ImageProvider {
    OPENAI_IMAGE,
    CLOUDFLARE_WORKERS_AI
}
