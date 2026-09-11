package io.memoryos.chat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Identity includes the operation and target; retries never append another reply. */
public record ChatCommand(Operation operation, UUID targetMessageId, UUID requestId,
                          String text, @Nullable UUID modelConfigurationId) {
    public enum Operation { SEND, EDIT, REGENERATE }
    public ChatCommand {
        if (operation == null || targetMessageId == null || requestId == null || text == null
                || text.length() > 32000 || (operation != Operation.REGENERATE && text.isBlank())
                || (operation == Operation.REGENERATE && !text.isEmpty()))
            throw ChatException.invalid("Invalid chat command.");
    }
}
