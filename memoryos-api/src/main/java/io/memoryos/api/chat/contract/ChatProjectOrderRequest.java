package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "ProjectOrderRequest", description = "Every project of the actor exactly once, in the chosen order")
public record ChatProjectOrderRequest(List<UUID> projectIds) {}
