package io.memoryos.chat.image;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record GeneratedImage(UUID id, String mediaType, @Nullable String revisedPrompt, boolean deleted) {}
