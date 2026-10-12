package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatHistoryVisibility;
import io.memoryos.chat.ChatSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChatSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Deep research: agentic research across the web and connected sources; uses significantly more tokens per query.")
        boolean deepResearchEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Who in the organization may read other people's conversations: NORMAL names the asker, ANONYMIZED hides only their name and e-mail, DISABLED refuses every read")
        ChatHistoryVisibility chatHistoryVisibility,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Every turn answers from the organization's documents only")
        boolean groundedAnswers,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "In that mode, a person may still turn Web search on for a turn")
        boolean groundedAllowWeb,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {
    public static ChatSettingsResponse from(ChatSettingsService.View view) {
        return new ChatSettingsResponse(view.deepResearchEnabled(), view.chatHistoryVisibility(),
                view.groundedAnswers(), view.groundedAllowWeb(), view.revision());
    }
}
