package io.memoryos.ai.systemone;

/**
 * The implemented System One connection types, persisted and in the API. Identity only: what each needs comes from
 * its adapter through {@link SystemOneAdapterRegistry}.
 */
public enum SystemOneProvider {
    TYPESAFE, CLOUDFLARE, NINEROUTER, LAYA, SYSTEMONE_COMPATIBLE
}
