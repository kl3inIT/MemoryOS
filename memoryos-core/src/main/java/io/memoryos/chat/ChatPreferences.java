package io.memoryos.chat;

import io.memoryos.ai.ReasoningEffort;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One member's Chat preferences (Onyx Settings: Work Role, Personal Preferences, Default Model, Default Temperature,
 * Default Reasoning Level, Chat Auto-scroll). Empty text means not set, and a null default defers to the model
 * configuration. {@code channelPersonaId} is the assistant the member's direct messages to a bot answer with, in place
 * of the bot's own (MEM-299). {@code pasteAsTile} is Onyx's Collapse Large Pastes: the composer turns a large paste
 * into a tile instead of inserting it (MEM-148).
 */
public record ChatPreferences(String workRole, String personalPreferences, @Nullable UUID defaultModelId,
                              @Nullable Double temperatureDefault, @Nullable ReasoningEffort reasoningEffortDefault,
                              boolean autoScroll, @Nullable UUID channelPersonaId,
                              boolean pasteAsTile) {
    public static final int MAX_WORK_ROLE = 200;
    public static final int MAX_PERSONAL_PREFERENCES = 2000;
    public static final ChatPreferences DEFAULT = new ChatPreferences("", "", null, null, null, true, null, false);

    public ChatPreferences(String workRole, String personalPreferences, @Nullable UUID defaultModelId,
                           @Nullable Double temperatureDefault, @Nullable ReasoningEffort reasoningEffortDefault,
                           boolean autoScroll) {
        this(workRole, personalPreferences, defaultModelId, temperatureDefault, reasoningEffortDefault, autoScroll, null,
                false);
    }

    public ChatPreferences withChannelPersona(@Nullable UUID persona) {
        return new ChatPreferences(workRole, personalPreferences, defaultModelId, temperatureDefault,
                reasoningEffortDefault, autoScroll, persona, pasteAsTile);
    }

    public ChatPreferences {
        workRole = workRole == null ? "" : workRole.strip();
        personalPreferences = personalPreferences == null ? "" : personalPreferences.strip();
    }
}
