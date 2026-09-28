package io.memoryos.ai;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FlowModelDefault(ModelFlow flow, @Nullable UUID modelConfigurationId, long revision) {}
