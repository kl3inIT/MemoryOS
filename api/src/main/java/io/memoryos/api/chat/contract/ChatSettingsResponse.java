package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record ChatSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Deep research: agentic research across the web and connected sources; uses significantly more tokens per query.")
        boolean deepResearchEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Days of inactivity after which a conversation is deleted; null is no policy")
        @Nullable Integer chatRetentionDays,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {
    public static ChatSettingsResponse from(ChatSettingsService.View view) {
        return new ChatSettingsResponse(view.deepResearchEnabled(), view.chatRetentionDays(), view.revision());
    }
}
