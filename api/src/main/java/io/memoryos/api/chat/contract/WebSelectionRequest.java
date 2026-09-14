package io.memoryos.api.chat.contract;

import io.memoryos.chat.web.WebProvider;
import org.jspecify.annotations.Nullable;

public record WebSelectionRequest(boolean search, @Nullable WebProvider provider) {}
