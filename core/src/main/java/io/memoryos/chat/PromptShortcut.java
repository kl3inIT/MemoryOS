package io.memoryos.chat;

import java.util.UUID;

public record PromptShortcut(UUID id, String name, String content, boolean active, boolean isPublic, boolean hidden, long revision) {}
