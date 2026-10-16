package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "PinsRequest")
public record ChatPersonaPinsRequest(List<UUID> personaIds) {}
