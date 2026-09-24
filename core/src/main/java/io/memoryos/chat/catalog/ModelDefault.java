package io.memoryos.chat.catalog;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ModelDefault(@Nullable UUID modelConfigurationId, long revision) {}
