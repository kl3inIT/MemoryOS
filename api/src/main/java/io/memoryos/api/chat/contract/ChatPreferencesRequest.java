package io.memoryos.api.chat.contract;

import io.memoryos.chat.preferences.ChatPreferences;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The member's complete Chat preferences; empty text clears a value and a null model uses the Tenant default. */
@Schema(name = "ChatPreferencesInput")
public record ChatPreferencesRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String workRole,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000) String personalPreferences,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) @Nullable UUID defaultModelId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatPreferences.StartPage startPage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean autoScroll,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean collapsePastes
) {
    public ChatPreferences toInput() {
        return new ChatPreferences(workRole, personalPreferences, defaultModelId, startPage, autoScroll, collapsePastes);
    }
}
