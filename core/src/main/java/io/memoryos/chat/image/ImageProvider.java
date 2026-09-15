package io.memoryos.chat.image;

/** Implemented image-generation protocols; native model tools are not image-provider connections. */
public enum ImageProvider {
    OPENAI_IMAGE,
    CLOUDFLARE_WORKERS_AI;

    public boolean requiresKey() { return true; }
}
