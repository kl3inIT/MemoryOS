package io.memoryos.chat.preferences;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One member's Chat preferences (Onyx Settings: Work Role, Personal Preferences, Default Model, Default App Mode,
 * Chat Auto-scroll, Collapse Large Pastes). Empty text means not set.
 */
public record ChatPreferences(String workRole, String personalPreferences, @Nullable UUID defaultModelId,
                              StartPage startPage, boolean autoScroll, boolean collapsePastes) {
    public static final int MAX_WORK_ROLE = 200;
    public static final int MAX_PERSONAL_PREFERENCES = 2000;
    public static final ChatPreferences DEFAULT = new ChatPreferences("", "", null, StartPage.CHAT, true, true);

    public enum StartPage { CHAT, SEARCH }

    public ChatPreferences {
        workRole = workRole == null ? "" : workRole.strip();
        personalPreferences = personalPreferences == null ? "" : personalPreferences.strip();
        startPage = startPage == null ? StartPage.CHAT : startPage;
    }
}
