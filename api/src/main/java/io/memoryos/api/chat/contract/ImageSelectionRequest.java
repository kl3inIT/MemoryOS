package io.memoryos.api.chat.contract;

import io.memoryos.chat.image.ImageProvider;
import org.jspecify.annotations.Nullable;

/** Null provider disables image generation for the Tenant. */
public record ImageSelectionRequest(@Nullable ImageProvider provider) {}
