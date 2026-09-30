package io.memoryos.connector.sync;

/**
 * What a provider offers beyond reading content.
 *
 * @param permissionSync whether the provider's own sharing settings are read, so a Source may admit readers by them
 */
public record SourceProviderCapabilities(boolean permissionSync) {
}
