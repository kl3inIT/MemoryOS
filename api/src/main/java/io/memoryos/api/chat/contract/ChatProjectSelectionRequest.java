package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ProjectSelection")
public record ChatProjectSelectionRequest(@Schema(types = {"string", "null"}, format = "uuid") @Nullable UUID projectId) {}
