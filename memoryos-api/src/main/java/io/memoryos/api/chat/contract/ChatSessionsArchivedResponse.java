package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatSessionsArchived")
public record ChatSessionsArchivedResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int archived) {}
