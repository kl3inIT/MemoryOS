package io.memoryos.ai.systemone;

import org.jspecify.annotations.Nullable;

/**
 * What a System One connection type needs.
 *
 * @param requiresKey  a connection is unusable without a stored credential
 * @param endpoint     what the endpoint field holds
 * @param defaultModel the model a new connection starts with; empty when the administrator must name one
 * @param inputPrice   the published USD price per million input tokens of the default model; null when none is
 */
public record SystemOneCapabilities(boolean requiresKey, Endpoint endpoint, String defaultModel,
                                    @Nullable Double inputPrice) {
    public enum Endpoint {
        /** The service has one address; the connection stores none. */
        FIXED,
        /** The base address of a self-hosted server or a gateway, ending in its version path. */
        URL,
        /** An account identifier the address is built from. */
        ACCOUNT
    }
}
