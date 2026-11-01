package io.memoryos.api.chat.contract;

import io.memoryos.chat.PromptShortcut;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "PromptShortcut")
public record ChatPromptShortcutResponse(
        UUID id,
        String name,
        String content,
        boolean active,
        boolean isPublic,
        boolean hidden,
        long revision
) {
    public static ChatPromptShortcutResponse from(PromptShortcut value) {
        return new ChatPromptShortcutResponse(value.id(), value.name(), value.content(), value.active(), value.isPublic(),
                value.hidden(), value.revision());
    }
}
